package uk.gov.hmrc.api.specs

import com.github.tomakehurst.wiremock.client.WireMock.*
import uk.gov.hmrc.api.specs.support.TestData

class ExternalTestSwitchSpec extends BaseSpec {
  Feature("DL-21057: external-test-stubs is disabled") {
    
    // Configured stub urls and a stub error must not divert feedback from RDS
    Scenario("Generate feedback through RDS when the stub switch is off") {
      val response = api.feedback(
        headers = TestData.defaultHeaders + ("Gov-Test-Scenario" -> "INVALID_REQUEST")
      )
      
      assertFeedbackSuccess(response)
      
      downstream.server.verify(
        1,
        postRequestedFor(urlPathEqualTo("/rds/assessments/generate"))
      )
      
      assertNotCalled("/feedback")
      assertNotCalled("/acknowledge")
    }
    
    // Configured stub urlls and a stub error header must not divert acknowledgement from RDS
    Scenario("Acknowledge through RDS when the stub swtich is off") {
      val response = api.acknowledge(
        headers = TestData.defaultHeaders + ("Gov-Test-Scenario" -> "INVALID_CREDENTIALS")
      )
      
      assertAcknowledgementSuccess(response)
      
      downstream.server.verify(
        1,
        postRequestedFor(urlPathEqualTo("/rds/assessments/acknowledge"))
      )
      
      assertNotCalled("/feedback")
      assertNotCalled("/acknowledge")
    }
  }
}
