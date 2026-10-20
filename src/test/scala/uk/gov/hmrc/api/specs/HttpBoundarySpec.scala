package uk.gov.hmrc.api.specs

import uk.gov.hmrc.api.specs.support.TestData

class HttpBoundarySpec extends BaseSpec {
  Feature("HTTP routing and request parsing") {

    // omits version Accept Header and checks rejection before authentication
    Scenario("Return 406 when the API version Accept header is missing") {
      val response = api.feedback(
        headers = TestData.defaultHeaders - "Accept"
      )

      response.status shouldBe 406
      (response.json \ "code").as[String] shouldBe "ACCEPT_HEADER_INVALID"

      assertNotCalled("/auth/authorise")
    }

    // send incomplete JSON over HTTP and checks that the body parse rejects it
    Scenario("Reject malformed JSON with 400") {
      val response = api.rawFeedback("{\"periodKey\":")

      response.status shouldBe 400

      assertNotCalled(s"/internal/validate/${TestData.vrn}")
      assertNotCalled(("/rds/assessments.generate"))
    }
  }
}
