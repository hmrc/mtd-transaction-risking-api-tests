package uk.gov.hmrc.api.specs

import com.github.tomakehurst.wiremock.client.WireMock.*

class RdsAuthFailureSpec extends BaseSpec {
  private val tokenPath = "/SASLOGON/oauth/token"
  
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
