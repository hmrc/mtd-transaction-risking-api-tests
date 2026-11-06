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

import com.github.tomakehurst.wiremock.client.WireMock.*

class RdsAuthFailureSpec extends BaseSpec {
  private val tokenPath = "/SASLogon/oauth/token"

  Feature("DL-20935: RDS authentication is unavailable") {

    // Check that token-service 404 gives the feedback caller 503
    // and prevents the report-generation request from being sent
    Scenario("Return 503 for feedback hen RDS authentication returns 404") {
      downstream.reply(tokenPath, 404, "{}")

      assertError(api.feedback(), 503, "SERVICE_UNAVAILABLE")

      downstream.server.verify(
        1,
        postRequestedFor(urlPathEqualTo(tokenPath))
      )

      assertNotCalled("/rds/assessments/generate")
    }

    // Check that a token-service 404 gives the acknowledgement caller 503
    // and prevents the downstream acknowledment from being sent
    Scenario("Return 503 for acknowledgement when RDS authentication returns 404") {
      downstream.reply(tokenPath, 404, "{}")

      assertError(api.acknowledge(), 503, "SERVICE_UNAVAILABLE")

      downstream.server.verify(
        1,
        postRequestedFor(urlPathEqualTo(tokenPath))
      )

      assertNotCalled("/rds/assessments/acknowledge")
    }
  }
}
