/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.api.specs

import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.GivenWhenThen
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, GivenWhenThen}
import org.scalatest.concurrent.Eventually
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.JsObject
import uk.gov.hmrc.api.conf.TestEnvironment
import uk.gov.hmrc.api.specs.support.{ApiClient, ApiResponse, DownstreamStub}
import java.util.UUID

trait BaseSpec
    extends AnyFeatureSpec
    with GivenWhenThen
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Eventually {
  protected def applicationServiceName: String =
    "mtd-transaction-risking"

  protected lazy val api        = new ApiClient(applicationServiceName)
  protected lazy val downstream = new DownstreamStub

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(Span(5, Seconds), Span(100, Millis))

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    require(
      TestEnvironment.environment === "local",
      "This suite requires the local application and test-managed stubs"
    )

    downstream.start()

    try
      Seq(
        api.baseUrl,
        TestEnvironment.url("mtd-transaction-risking-stub")
      ).foreach { url =>
        withClue(s"Start $url before running the tests. Health response: ") {
          api.send(s"$url/ping/ping").status shouldBe 200
        }
      }
    catch {
      case error: Throwable =>
        downstream.stop()
        throw error
    }
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    downstream.reset()
  }

  override protected def afterAll(): Unit =
    try downstream.stop()
    finally super.afterAll()

  protected def assertError(
    response: ApiResponse,
    status: Int,
    code: String
  ): Unit = {
    withClue(s"Response body: ${response.body}. ") {
      response.status                     shouldBe status
      (response.json \ "code").as[String] shouldBe code
    }

    assertCorrelationHeader(response)
  }

  protected def assertCorrelationHeader(response: ApiResponse): Unit = {
    val value = response
      .header("X-CorrelationId")
      .getOrElse(fail("Missing X-CorrelationId response header"))

    UUID.fromString(value).toString shouldBe value
  }

  protected def assertFeedbackSuccess(response: ApiResponse): Unit = {
    withClue(s"Response body: ${response.body}. ") {
      response.status shouldBe 200
    }

    assertCorrelationHeader(response)

    val reportId = (response.json \ "reportId").as[String]

    UUID.fromString(reportId).toString shouldBe reportId

    (response.json \ "englishFeedback").as[Seq[JsObject]] should not be empty
    (response.json \ "welshFeedback").as[Seq[JsObject]]   should not be empty

    awaitInteraction(response)
  }

  protected def assertAcknowledgmentSucess(response: ApiResponse): Unit = {
    withClue(s"Response body: ${response.body}. ") {
      response.status shouldBe 204
    }

    response.body shouldBe empty
    assertCorrelationHeader(response)
    awaitInteraction(response)
  }

  // wait for requests asynchronus RSD call before resetting wireMock
  protected def awaitInteraction(response: ApiResponse): Unit = {
    val correlationId = response
      .header("X-CorrelationId")
      .getOrElse(fail("Missing X-CorrelationId"))

    eventually {
      downstream.server.verify(
        1,
        postRequestedFor(urlPathEqualTo("/rsd/receive-and-store"))
          .withHeader("CorrelationId", equalTo(correlationId))
      )
    }
  }
  protected def assertNotCalled(path: String): Unit           =
    downstream.server.verify(
      0,
      postRequestedFor(urlPathEqualTo(path))
    )
}
