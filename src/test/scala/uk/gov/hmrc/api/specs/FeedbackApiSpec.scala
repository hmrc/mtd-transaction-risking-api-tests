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
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.api.specs.support.TestData

class FeedbackApiSpec extends BaseSpec {
  Feature("POST /feedback/:vrn") {

    // submit valid figures and check bilingual feedback and report identifiers
    Scenario("Return 200 with bilingual feedback for a valid VAT return") {
      Given("an authorised taxpayer and a valid VAT return")
      When("feedback is requested through the application")

      val response = api.feedback()

      Then("the response contains the report and feedback from RDS")
      assertFeedbackSuccess(response)

      (response.json \ "correlationId").as[String] shouldBe
        TestData.rdsCorrelationId

      val english = (response.json \ "englishFeedback").as[Seq[JsObject]]
      val welsh = (response.json \ "welshFeedback").as[Seq[JsObject]]

      english should have size 1
      welsh should have size 1

      (english.head \ "itemNumber").as[String] shouldBe "1"
      (welsh.head \ "itemNumber").as[String] shouldBe "1"

      (english.head \ "body").as[String] shouldBe
       "The VAT due on sales figure appears lower than expected based on your total sales value."

      (english.head \ "itemNumber").as[String] shouldBe "vatDueSales"
      (welsh.head \ "itemNumber").as[String] shouldBe "vatDueSales"

      (english.head \ "links" \ 0 \ "url").as[String] shouldBe
        "https://www.gov.uk/guidance/vat-notice-700-the-vat-guide"
    }

    // RD04 selects no feedback: item 0 remains, with no actionable path or correlation ID
    Scenario("Return item 0 without actionable paths when RDS has no feedback") {
      val response = api.feedback(TestData.validVatReturn("RD04"))

      response.status shouldBe 200
      assertCorrelationHeader(response)

      (response.json \ "correlationId").toOption shouldBe None

      Seq("englishFeedback", "welshFeedback").foreach { language =>
        val messages = (response.json \ language).as[Seq[JsObject]]

        messages should have size 1
        (messages.head \ "itemNumber").as[String] shouldBe "0"
        (messages.head \ "path").toOption shouldBe None 
      }
      
      assertNotCalled("/rsd/receive-and-store")
      assertNotCalled("/nrs-orchestrator/submission")
    }
    
    // Checks the VAT validation request and the figures and obligation dates send to RDS
    Scenario("Forward VAT figures and the resolved obligation to downstream services") {
      val body = TestData.validVatReturn()
      
      assertFeedbackSuccess(api.feedback(body))
      
      downstream.server.verify(
        postRequestedFor(
          urlPathEqualTo(s"/internal/validate/${TestData.vrn}")
        ).withRequestBody(equalToJson(Json.stringify(body)))
      )
      
      val calls = downstream.server.findAll(
        postRequestedFor(urlPathEqualTo("/rds/assessments/generate"))
      )
      
      calls.size() shouldBe 1 
      
      val inputs = (Json.parse(calls.get(0).getBodyAsString) \ "inputs")
        .as[Seq[JsObject]]
        .map { input =>
          (input \ "name").as[String] -> (input \ "value").as[JsValue] 
        }
        .toMap
      
      // JSON number comparison treats 100 and 100.00 ans the same amount
      body.fields.foreach { case (name, value) =>
        inputs(name) shouldBe value
      }
      
      inputs("startDate").as[String] shouldBe "2026-01-01"
      inputs("endDate").as[String] shouldBe "2026-03-31"
    }
  }
}
