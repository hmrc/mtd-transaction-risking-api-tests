package uk.gov.hmrc.api.specs.support

import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.api.conf.TestEnvironment

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Locale
import scala.jdk.CollectionConverters

final case class ApiResponse (
  status: Int,
  body: String,
  headers: Map[String, List[String]]
) {
  def json: JsValue = Json.parse(body)

  def header(name: String): Option[String] =
    headers.get(name.toLowerCase(Locale.ROOT)).flatMap(_.headOption)
}

final class ApiClient(serviceName: String = "mtd-transaction-risking") {
  private val config = ConfigFactory.load()
  private val timeout = Duration.ofSeconds(config.getLong("local.request-timeout-seconds"))

  private val client = HttpClient
    .newBuilder()
    .connectTimeout(timeout)
    .followRedirects(HttpClient.Redirect.NEVER)
    .version(HttpClient.Version.HTTP_1_1)
    .build()

  val baseUrl: String = TestEnvironment.url(serviceName)

  def send (
    url: String,
    method: String = "GET",
    body: Option[String] = None,
    headers: Map[String, String] = Map.empty
  ): ApiResponse = {
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)

    headers.foreach { case (name, value ) =>
      builder.header(name, value)
    }

    val publisher = {
      body.fold(HttpRequest.BodyPublishers.noBody()) (
        HttpRequest.BodyPublishers.ofString(_, UTF_8)
      )

    val response =
      client.send(
        builder.method(method, publisher).build(),
        HttpResponse.BodyHandlers.ofString(UTF_8)
      )

    ApiResponse(
      response.statusCode(),
      response.body(),
      response
        .headers()
        .map()
        .asScala
        .map { case (name, values) =>
          name.toLowerCase(Locale.ROOT) -> values.asScala.toList
        }
        .toMap
    )
    }

    def feedback (
     body: JsValue = TestData.validVatReturn(),
     vrn: String = TestData.vrn,
     headers: Map[String, String] = TestData.defaultHeaders
    ): ApiResponse =
      rawFeedback(Json.stringify(body), vrn, headers)

    def rawFeedback (
      body: String,
      vrn: String = TestData.vrn,
      headers: Map[String, String] = TestData.defaultHeaders
    ): ApiResponse = {
      val query = presentedDateTime.fold("") { value =>
        s"?presentedDateTime=${URLEncoder.encode(value, UTF_8)}"
      }

    send(
      s"$baseUrl/acknowledge/$vrn/$vrn/$reportId/$correlationId$query",
      "POST",
      None,
      headers
    )
  }
}
