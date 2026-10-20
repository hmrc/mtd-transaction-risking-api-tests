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

package uk.gov.hmrc.api.specs.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.typesafe.config.ConfigFactory
import play.api.libs.json.Json
import uk.gov.hmrc.api.conf.TestEnvironment

final class DownstreamStub {
  val server = new WireMockServer( 
    wireMockConfig()
      .bindAddress("127.0.0.1")
      .port(ConfigFactory.load().getInt("local.test-harness.port"))
  )
  
  def start(): Unit = server.start()
  
  def stop(): Unit = server.stop()
  
  def reset(): Unit = {
    server.resetAll()
    
    server.stubFor(
      any(anyUrl())
        .atPriority(100)
        .willReturn(
          aResponse().proxiedFrom(
            TestEnvironment.url("mtd-transaction-risking-stub")
          )
        )
    )
    authorise()
  }
  
  def authorise(vrn: String = TestData.vrn): Unit = {
    val identity = Json.obj(
      "internalId" -> "local-test-user",
      "affinityGroup" -> "Individual",
      "optionalCredentials" -> Json.obj(
        "providerId" -> "local-test-user",
        "providerType" -> "GovermentGateway"
      ),
      "confidenceLevel" -> 200,
      "agentInformation" -> Json.obj(),
      "loginTimes" -> Json.obj(
        "currentLogin" -> "2026-10-19T09:00:00:000Z"
      ),
      "allEnrolments" -> Json.arr(
        Json.obj(
          "key" -> "HMRC-MTD-VAT",
          "identifiers" -> Json.arr(
            Json.obj("key" -> "VRN", "value" -> vrn)
          ),
          "state" -> "Activated"
        )
      )
    )
    reply("/auth/authorise", 200, Json.stringify(identity))
  }
  
  def reply(path: String, status: Int, body: String): Unit = {
    server.stubFor(
      post(urlPathEqualTo(path))
        .atPriority(1)
        .willReturn(
          aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(body)
        )  
    )
    ()
  }
}
