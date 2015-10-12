import sbt._
import play.sbt.PlayImport

object Dependencies {

  //versions
  val awsClientVersion = "1.9.30"
  //libraries
  val scalaUri = "com.netaporter" %% "scala-uri" % "0.4.6"
  val identityCookie = "com.gu.identity" %% "identity-cookie" % "3.44"
  val identityPlayAuth = "com.gu.identity" %% "identity-play-auth" % "0.10"
  val playWS = PlayImport.ws
  val playCache = PlayImport.cache
  val playFilters = PlayImport.filters
  val scalaTest =  "org.scalatestplus" %% "play" % "1.4.0-M3" % "test"
  val specs2 = PlayImport.specs2 % "test"
  val awsWrap = "com.github.dwhjames" %% "aws-wrap" % "0.7.2"
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % "1.9.31"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.1.1"

  //projects

  val apiDependencies = Seq(scalaUri, identityCookie, identityPlayAuth,
    playWS, playCache, playFilters, awsWrap, awsCloudWatch, scalaz,
    specs2, scalaTest)

}
