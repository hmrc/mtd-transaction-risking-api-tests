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

import uk.gov.hmrc.api.specs.support.TestData

class HttpBoundarySpec extends BaseSpec {
  Feature("HTTP routing and request parsing") {

    // omits version Accept Header and checks rejection before authentication
    Scenario("Return 406 when the API version Accept header is missing") {
      val response = api.feedback(
        headers = TestData.defaultHeaders - "Accept"
      )

      response.status                     shouldBe 406
      (response.json \ "code").as[String] shouldBe "ACCEPT_HEADER_INVALID"

      assertNotCalled("/auth/authorise")
    }

    // send incomplete JSON over HTTP and checks that the body parse rejects it
    Scenario("Reject malformed JSON with 400") {
      val response = api.rawFeedback("{\"periodKey\":")

      response.status shouldBe 400

      assertNotCalled(s"/internal/validate/${TestData.vrn}")
      assertNotCalled("/rds/assessments/generate")
    }
  }
}
