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
import play.api.libs.json.Json
import uk.gov.hmrc.api.specs.support.{ApiResponse, TestData}
import com.atlassian.oai.validator.report.{LevelResolver, ValidationReport}

import scala.jdk.CollectionConverters.*

class StubbedContractSpec extends BaseSpec {
  override protected def applicationServiceName: String      =
    "mtd-transaction-risking-stubbed"
  private def headers(scenario: String): Map[String, String] =
    TestData.defaultHeaders + ("Gov-Test-Scenario" -> scenario)

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

    // This test is failing as there is updates to OAS that need to be done regarding expected value for itemNumber
    // genereats a report through feedback-sstub and validates the request and actual 200 response
    Scenario("Generates a report through the stubbed path and satisfy the OAS") {
      val body = TestData.validVatReturn()

      val response = api.feedback(
        body = body,
        headers = headers("-")
      )

      response.status shouldBe 200
      assertCorrelationHeader(response)

      verifyStubbedPath("/feedback", "-")

      validateContract(
        s"/feedback/${TestData.vrn}",
        response,
        scenario = "-",
        body = Some(Json.stringify(body))
      )
    }

    // Acknowledge independently through the acknowledge-stub and validatss the request and 204 response
    Scenario("Acknowledge through the stubbed path and satisfy the OAS") {
      val response = api.acknowledge(
        headers = headers("-")
      )

      response.status shouldBe 204
      response.body   shouldBe empty
      assertCorrelationHeader(response)

      verifyStubbedPath("/acknowledge", "-")

      validateContract(
        s"/acknowledge/${TestData.vrn}/${TestData.reportId}/${TestData.rdsCorrelationId}",
        response,
        scenario = "-",
        query = Map("presentedDateTime" -> TestData.presentedDateTime)
      )
    }

    // Uses valid URL identifiers so INVALID_CORRELATIONID reaches the mtd-transaction-risking-stub repo
    Scenario("Relay the stubs CORRELATION_ID_INVALID error and satisfy the OAS") {
      assertStubbedAcknowledgementError(
        "INVALID_CORRELATIONID",
        "CORRELATION_ID_INVALID"
      )
    }

    // Checks the updated CORRELATION_ID selector returns 400 with error code
    Scenario("Relay the stubs updated acknowledgement validation error and satisfy the OAS") {
      assertStubbedAcknowledgementError(
        "CORRELATION_ID",
        "ACKNOWLEDGEMENT_VALIDATION_FAILED"
      )
    }
  }

  private def assertStubbedAcknowledgementError(
    scenario: String,
    code: String
  ): Unit = {
    val response = api.acknowledge(
      headers = headers(scenario)
    )

    assertError(response, 400, code)
    verifyStubbedPath("/acknowledge", scenario)

    validateContract(
      s"/acknowledge/${TestData.vrn}/${TestData.reportId}/${TestData.rdsCorrelationId}",
      response,
      scenario,
      query = Map("presentedDateTime" -> TestData.presentedDateTime)
    )
  }

  private def verifyStubbedPath(path: String, scenario: String): Unit = {
    downstream.server.verify(
      1,
      postRequestedFor(urlPathEqualTo(path))
        .withHeader("Gov-Test-Scenario", equalTo(scenario))
    )

    assertNotCalled(s"/internal/validate/${TestData.vrn}")
    assertNotCalled("/check/insights")
    assertNotCalled("/rds/assessments/generate")
    assertNotCalled("/rds/acknowledge")
    assertNotCalled("/rsd/receive-and-store")
  }

  private def validateContract(
    localPath: String,
    response: ApiResponse,
    scenario: String,
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
      s"OAS validatiomn errors:\n${report.getMessages.asScala.mkString("\n")}\n"
    ) {
      report.hasErrors shouldBe false
    }
  }
}
