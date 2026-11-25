/*
 * Copyright 2026 HM Revenue & Customs
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

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.{SimpleRequest, SimpleResponse}
import com.github.tomakehurst.wiremock.client.WireMock.*
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.api.specs.support.{ApiResponse, TestData}
import com.atlassian.oai.validator.report.{LevelResolver, ValidationReport}

import scala.jdk.CollectionConverters.*

class StubbedContractSpec extends BaseSpec {
  override protected def applicationServiceName: String              =
    "mtd-transaction-risking-stubbed"
  private def headers(scenario: Option[String]): Map[String, String] =
    scenario.fold(TestData.defaultHeaders)(name => TestData.defaultHeaders + ("Gov-Test-Scenario" -> name))

  // load unchanged OAS from mtd-transaction-risking-repo
  private lazy val validator = {
    val specification = api.send(
      s"${api.baseUrl}/api/conf/1.0/application.yaml"
    )

    withClue("Unable to retrieve the applications OAS: ") {
      specification.status shouldBe 200
    }

    OpenApiInteractionValidator
      .createForInlineApiSpecification(specification.body)
      .withLevelResolver(
        LevelResolver
          .create()
          .withLevel(
            "validation.request.accept.notAllowed",
            ValidationReport.Level.IGNORE
          )
          .build()
      )
      .build()
  }

  Feature("OAS contracts for the applications dedicated stubbed paths") {

    // generates a report through feedback-stub and validates the request and response against the OAS
    Scenario("Generates a report through the stubbed path and satisfy the OAS") {
      val body = TestData.validVatReturn()

      val response = api.feedback(
        body = body,
        headers = headers(None)
      )

      response.status shouldBe 200
      assertCorrelationHeader(response)

      verifyStubbedPath("/feedback", None)

      validateContract(
        s"/feedback/${TestData.vrn}",
        response,
        scenario = None,
        body = Some(Json.stringify(body))
      )
    }

    // Check the stubbed INVALID_REQUEST response includes both field errors
    // and that the request and response satisfy the applications OAS
    Scenario("Return detailed INVALID_REQUEST feedback and satisfy the OAS") {
      val scenario = Some("INVALID_REQUEST")
      val body     = TestData.validVatReturn()

      val response = api.feedback(
        body = body,
        headers = headers(scenario)
      )

      assertError(response, 400, "INVALID_REQUEST")

      val errors = (response.json \ "errors").as[Seq[JsObject]]
      errors should have size 2

      errors.map { error =>
        (error \ "code").as[String] -> (error \ "path").as[String]
      }.toMap shouldBe Map(
        "VAT_NET_VALUE"   -> "/netVatDue",
        "VAT_TOTAL_VALUE" -> "/totalVatDue"
      )

      verifyStubbedPath("/feedback", scenario)

      validateContract(
        s"/feedback/${TestData.vrn}",
        response,
        scenario = scenario,
        body = Some(Json.stringify(body))
      )
    }

    // Acknowledge independently through the acknowledge-stub and validatss the request and 204 response
    Scenario("Acknowledge through the stubbed path and satisfy the OAS") {
      val response = api.acknowledge(
        headers = headers(None)
      )

      response.status shouldBe 204
      response.body   shouldBe empty
      assertCorrelationHeader(response)

      verifyStubbedPath("/acknowledge", None)

      validateContract(
        s"/acknowledge/${TestData.vrn}/${TestData.reportId}/${TestData.rdsCorrelationId}",
        response,
        scenario = None,
        query = Map("presentedDateTime" -> TestData.presentedDateTime)
      )
    }

    Seq(
      (
        "INVALID_CORRELATIONID",
        400,
        "CORRELATION_ID_INVALID",
        "The provided correlation ID is invalid"
      ),
      (
        "ACKNOWLEDGEMENT_VALIDATION_FAILED",
        400,
        "ACKNOWLEDGEMENT_VALIDATION_FAILED",
        "The acknowledgement request failed validation"
      ),
      (
        "INVALID_CREDENTIALS",
        401,
        "INVALID_CREDENTIALS",
        "Invalid authentication information provided"
      ),
      (
        "NOT_FOUND",
        404,
        "MATCHING_RESOURCE_NOT_FOUND",
        "A resource with the name in the request can not be found in the API"
      ),
      (
        "INVALID_REPORTID",
        400,
        "REPORT_ID_INVALID",
        "The provided report ID is invalid"
      ),
      (
        "INVALID_DATETIME",
        400,
        "PRESENTED_DATE_TIME_INVALID",
        "The presentedDateTime query parameter is missing or invalid"
      ),
      (
        "NOT_AUTHORISED",
        403,
        "CLIENT_OR_AGENT_NOT_AUTHORISED",
        "The client and/or agent is not authorised"
      )
    ).foreach { case (scenario, status, code, message) =>
      Scenario(s"Acknowledge $scenario returns $status and satisfies the OAS") {
        assertStubbedAcknowledgementError(
          scenario,
          status,
          code,
          message
        )
      }
    }
  }

  private def assertStubbedAcknowledgementError(
    scenario: String,
    status: Int,
    code: String,
    message: String
  ): Unit = {
    val response = api.acknowledge(
      headers = headers(Some(scenario))
    )

    assertError(response, status, code)
    (response.json \ "message").as[String] shouldBe message
    verifyStubbedPath("/acknowledge", Some(scenario))

    validateContract(
      s"/acknowledge/${TestData.vrn}/${TestData.reportId}/${TestData.rdsCorrelationId}",
      response,
      Some(scenario),
      query = Map("presentedDateTime" -> TestData.presentedDateTime)
    )
  }

  private def verifyStubbedPath(path: String, scenario: Option[String]): Unit = {
    val request  = postRequestedFor(urlPathEqualTo(path))
    val expected = scenario match {
      case None       => request.withHeader("Gov-Test-Scenario", absent())
      case Some(name) => request.withHeader("GOV-Test-Scenario", equalTo(name))
    }
    downstream.server.verify(1, expected)

    assertNotCalled(s"/internal/validate/${TestData.vrn}")
    assertNotCalled("/check/insights")
    assertNotCalled("/rds/assessments/generate")
    assertNotCalled("/rds/assessments/acknowledge")
    assertNotCalled("/rsd/receive-and-store")
  }

  private def validateContract(
    localPath: String,
    response: ApiResponse,
    scenario: Option[String],
    body: Option[String] = None,
    query: Map[String, String] = Map.empty
  ): Unit = {

    // match interaction to documented operation
    val request = SimpleRequest.Builder.post(
      s"/organisations/vat-assist$localPath"
    )

    headers(scenario).foreach { case (name, value) =>
      request.withHeader(name, value)
    }

    query.foreach { case (name, value) =>
      request.withQueryParam(name, value)
    }

    body.foreach(value => request.withBody(value))

    val actualResponse = SimpleResponse.Builder.status(response.status)

    response.headers.foreach { case (name, values) =>
      actualResponse.withHeader(name, values.asJava)
    }

    if (response.body.nonEmpty) {
      actualResponse.withBody(response.body)
    }

    val report = validator.validate(
      request.build(),
      actualResponse.build()
    )

    withClue(
      s"OAS validation errors:\n${report.getMessages.asScala.mkString("\n")}\n"
    ) {
      report.hasErrors shouldBe false
    }
  }
}
