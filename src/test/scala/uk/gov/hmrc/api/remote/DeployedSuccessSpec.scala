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

package uk.gov.hmrc.api.remote

import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsArray, Json}
import uk.gov.hmrc.api.conf.TestEnvironment
import uk.gov.hmrc.api.specs.support.{ApiClient, ApiResponse, TestData}

import java.nio.file.{Files, Path}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class DeployedSuccessSpec extends AnyFeatureSpec with Matchers {

  private def requiredEnvironmentVariable(name: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse {
      fail(s"Set $name before running this spec in ${TestEnvironment.environment}")
    }

  private def authorisationFor(vrn: String): String = {
    val loginApi = new ApiClient("auth-login-api")

    val loginBody = Json.obj(
      "credId"             -> UUID.randomUUID().toString,
      "affinityGroup"      -> "Organisation",
      "credentialRole"     -> "User",
      "credentialStrength" -> "strong",
      "confidenceLevel"    -> 50,
      "excludeGnapToken"   -> true,
      "enrolments"         -> Json.arr(
        Json.obj(
          "key"         -> "HMRC-MTD-VAT",
          "state"       -> "Activated",
          "identifiers" -> Json.arr(
            Json.obj(
              "key"   -> "VRN",
              "value" -> vrn
            )
          )
        )
      )
    )

    val login = loginApi.send(
      s"${loginApi.baseUrl}/government-gateway/session/login",
      method = "POST",
      body = Some(Json.stringify(loginBody)),
      headers = Map("Content-Type" -> "application/json")
    )

    withClue("Test login failed: ") {
      login.status shouldBe 201
    }

    val authorisation = login
      .header("Authorization")
      .getOrElse {
        fail("Test login did not return an Authorization header")
      }
      .trim

    if (!authorisation.matches("Bearer\\s+\\S+")) {
      fail("Test login did not return a valid bearer token")
    }

    authorisation
  }

  private def checkCorrelationHeader(response: ApiResponse): Unit = {
    val value = response.header("X-CorrelationId").getOrElse {
      fail("The response did not contain X-CorrelationId")
    }

    UUID.fromString(value).toString shouldBe
      value.toLowerCase(java.util.Locale.ROOT)
  }

  Feature("Successful requests against the deployed service") {

    Scenario("Generate feedback and acknowledge the returned report in QA or Staging") {
      assume(
        Set("qa", "staging").contains(TestEnvironment.environment),
        "Run this spec with -Denvironment=qa or -Denvironment=staging"
      )

      val vrn = requiredEnvironmentVariable("TXR_TEST_VRN")

      val requestFile = Path.of(
        requiredEnvironmentVariable("TXR_TEST_VAT_RETURN_FILE")
      )

      if (!Files.isRegularFile(requestFile)) {
        fail(s"VAT request file does not exist: $requestFile")
      }

      val request = Json.parse(Files.readString(requestFile))
      val api     = new ApiClient()

      val headers = TestData.defaultHeaders.updated(
        "Authorization",
        authorisationFor(vrn)
      )

      val feedback = api.feedback(request, vrn, headers)

      withClue(s"Feedback response: ${feedback.body}\n") {
        feedback.status shouldBe 200
      }

      feedback.header("Content-Type").getOrElse("") should
        startWith("application/json")

      checkCorrelationHeader(feedback)

      (feedback.json \ "englishFeedback").as[JsArray].value should not be empty
      (feedback.json \ "welshFeedback").as[JsArray].value   should not be empty

      val reportId = (feedback.json \ "reportId").as[String]

      val correlationId =
        (feedback.json \ "correlationId").asOpt[String].getOrElse {
          fail(
            "Use agreed test data that generated actionable feedback with a correlationId"
          )
        }

      UUID.fromString(reportId).toString shouldBe
        reportId.toLowerCase(java.util.Locale.ROOT)

      correlationId should fullyMatch regex "[0-9A-Fa-f]{64}$"

      val acknowledged = api.acknowledge(
        reportId = reportId,
        correlationId = correlationId,
        presentedDateTime = Some(
          Instant.now().truncatedTo(ChronoUnit.SECONDS).toString
        ),
        vrn = vrn,
        headers = headers
      )

      withClue(s"Acknowledgement response: ${acknowledged.body}\n") {
        acknowledged.status shouldBe 204
      }

      acknowledged.body shouldBe empty
      checkCorrelationHeader(acknowledged)
    }
  }
}
