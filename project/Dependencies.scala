import sbt.*

object Dependencies {

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc" %% "api-test-runner" % "0.9.0" % Test,
    "org.wiremock" % "wiremock-standalone" % "3.13.1" % Test,
    "com.atlassian.oai" % "swagger-request-validator-core" % "2.46.1" % Test
  )

}
