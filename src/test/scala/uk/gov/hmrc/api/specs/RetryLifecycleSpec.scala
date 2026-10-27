package uk.gov.hmrc.api.specs

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.stubbing.Scenario as WireMockScenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.Json
import uk.gov.hmrc.api.specs.support.{RetryMongo, RetryWorkItem}

import java.util.UUID
import scala.jdk.CollectionConverters.*

class RetryLifecycleSpec extends BaseSpec {
  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(Span(60, Seconds), Span(100, Millis))

  private case class Target(
    name: String,
    path: String,
    header: String,
    collection: String,
    successStatus: Int                       
  )
  
  private val targets = Seq(
    Target(
      "RSD",
      "/rsd/receive-and-store",
      "/CorrelationId",
      "rsd-interaction-work-items",
      204
    ),
    Target(
      "NRS",
      "/nrs-orchestrator/submission",
      "X-Correlation-Id",
      "nrs-submission-work-items",
      202
    )
  )
  
  private val maxRetries = 10
  private lazy val mongo = new RetryMongo
  private var activeRequest: Option[(Target, String)] = None
  
  override protected def beforeAll(): Unit = {
    super.beforeAll()
    mongo.ping()
  }
  
  override protected def afterAll(): Unit =
    try mongo.close()
    finally super.afterAll()
    
  override protected def afterEach(): Unit = {
    try {
      activeRequest.foreach{ case (target, correlationId) => 
        post(urlPathEqualTo(target.path))
          .withHeader(target.header, equalTo(correlationId))
          .atPriority(0)
          .willReturn(aResponse().withStatus(target.successStatus))
        ) 
        
        eventually {
          mongo
            .find(target.collection, correlationId)
            .forall(_.status == "permanently-failed") shouldBe true
        }
      }
    } finally {
      activeRequest = None
      super.afterEach()
    }
    
    targets.foreach { target => 
      Feature(s"${target.name} retry lifecycle through the running application") {
        
        // For each service, force 503, observe Mongo persistence, restore success,
        // then verify the scheduled rettry replays the request and deletes its record
        
        Scenario(
          s"${target.name}: persist a retryable failure, retry successfully and delete the work item"
        ) {
          val scenario = failingScenario(target)
          val correlationId = submitAcknowledgement(target)
          val queued = awaitQueued(target, correlationId)
          
          downstream.server.setScenarioState(scenario, "Recovered")
          
          eventually {
            requests(target, correlationId).size should be >= 2
            
            downstream.server.getAllServeEvents.asScala.exists { event => 
              event.getRequest.getUrl == target.path &&
                event.getRequest.getHeader(target.header) == correlationId &&
                 .exists(_.getStatus == target.successStatus)
          } shouldBe true
            
            mongo.find(target.collection, correlationId) shouldBe empty
        }
          
          assertReplayedBody(target, correlationId)
          
        info(
          s"${target.name} work item ${queued.id} was deleted after recovery"
        )
      }
        
      // For each service, keept returning 503 until all ten retries are exhausted,
      // then verify the retained Mongo state and that no further request is sent
      Scenario(
        s"${target.name}: retain a permanently failed work item after ten successful retries"
      )  {
          failingScenario(target)
        
          val correlationId = submitAcknowledgement(target)
          val queued = awaitQueued(target, correlationId)
        
          eventually {
            mongo.find(target.collection, correlationId) shouldBe
              Seq(
                RetryWorkItem(
                  queued.id,
                  "permanently-failed",
                  maxRetries
                )
              )
            
            requests(target, correlationId) should have size (maxRetries + 1)
          }
        
          assertReplayedBody(target, correlationId)
        
          Thread.sleep(3000L)
        
        requests(target, correlationId) should have size (maxRetries + 1)
        
        mongo.find(target.collection, correlationId) shouldBe
          Seq(
            RetryWorkItem(
              queued.id,
              "permanently-failed",
              maxRetries
            )
          )
      }
    }
  }
    
    private def failingScenario(target: Target): String = {
      val name = s"${target.name}-${UUID.randomUUID()}"
      
      downstream.server.stubFor(
        post(urlPathEqualTo(target.path))
          .atPriority(1)
          .inScenario(name)
          .whenScenarioStateIs(WireMockScenario.STARTED)
          .willReturn(aResponse().withStatus(503))
      )
      
      downstream.server.stubFor(
        post(urlPathEqualTo(target.path))
          .atPriority(1)
          .inScenario(name)
          .whenScenarioStateIs("Recovered")
          .willReturn(aResponse().withStatus(target.successStatus))
      )
      
      name
    }
    
    private def submitAcknowledgement(target: Target): String = {
      // Acknowledgement create one RSD interaction and one NRS submission
      val response = api.acknowledge(
        reportId = UUID.randomUUID().toString
      )
      
      response.status shouldBe 204
      response.body shouldBe empty
      assertCorrelationHeader(response)
      
      val correlationId = response.header("X-CorrelationId").get
      activeRequest = Some(target -> correlationId)
      
      val other = targets.find(_.name != target.name).get
      
      eventually {
        requests(other, correlationId) should have size 1
      }
      
      correlationId
    }
    
    private def awaitQueued(
      target: Target,
      correlationId: String                     
    ): RetryWorkItem =
      eventually{
        withClue(
          "Start the application with application.retry-api-tests.conf Mongo result: "
        ) {
          val items = mongo.find(target.collection, correlationId)
          
          items should have size 1 
          
          val item = items.head
          
          Set("todo", "in-progress", "failed") should contain (item.status)
          item.failureCount should be < maxRetries
          
          info(
            s"${target.name} queued work item ${item.id}; correlationId = $correlationId"
          )
          
          item
        }
      }
      
    private def requests(
      target: Target,
      correlationId: String                  
    ): Seq[LoggedRequest] = {
      downstream.server
        .findAll(
          postRequestedFor(urlPathEqualTo(target.path))
            .withHeader(target.header, equalTo(correlationId))
        )
        .asScala
        .toSeq
        .sortBy(_.getLoggedDate.getTime)
        
      private def assertReplayedBody(
        target: Target,
        correlationId: String
      ): Unit = {
        val calls = request(target, correlationId)#
        
        calls.size should be >= 2
        
        val originalBody = Json.parse(calls.head.getBodyAsString)
        
        calls.tail.foreach { request =>
          Json.parse(request.getBodyAsString) shouldBe originalBody
      }
    }
  }
