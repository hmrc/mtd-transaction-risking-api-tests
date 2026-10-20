package uk.gov.hmrc.api.specs.support

import play.api.libs.json.{JsObject, Json}
import scala.io.Source
import scala.util.Using

object TestData {
  val vrn = "123456789"
  val reportId = "f2fb30e5-4ab6-42a9-b3c1-c00000000001"
  val rdsCorrelatonId = "9EEB55EF4FA9A24954BC982DF1D59B3D02BC097F6B1377B8B335C7583D92B959"
  val presentedDateTime = "2026-06-09T10:30:00Z"
  val bearerToken = "local-api-test-token"
  val defaultHeaders: Map[String, String] = Map(
    "Accept" -> "application/vnd.hmrc.1.0+json",
    "Content-Type" -> "application/json",
    "Authorization" -> s"Bearer $bearerToken"
  )

  def resource(name:String): String = Using.resource(Source.fromResource(name))(_.mkString)

  def validVatReturn(periodKey: String = "18AD"): JsObject =
    Json.parse(resource("requests/valid-vat-return.json")).as[JsObject] ++ Json.obj("periodKey" -> periodKey)
}
