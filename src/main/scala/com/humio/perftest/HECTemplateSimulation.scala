package com.humio.perftest

import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZonedDateTime }
import java.util.concurrent.TimeUnit
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.fusesource.scalate.{ Binding, TemplateEngine }

import scala.concurrent.duration._
import scala.util.Random

object HECTemplateSimulation {
  val random = new Random()
  val eventsPerBulk = Option(System.getProperty("bulksize")).getOrElse("100").toInt
  val dataspaces = Option(System.getProperty("dataspaces")).getOrElse("1").toInt
  val templateFile = Option(System.getProperty("template")).getOrElse("templates/test.ssp")

  // init template
  val simTemplate = new SimTemplate(templateFile)

  def request(): String = {
    val events =
      for (i <- 0 until eventsPerBulk) yield {
        // create content
        simTemplate.generate
      }
    events.mkString("\n")
  }
}

import HECTemplateSimulation._
class HECTemplateSimulation extends Simulation {

  val dataspaceFeeder = Iterator.continually(Map("dataspace" -> ("dataspace" + random.nextInt(dataspaces))))
  val requestFeeder = Iterator.continually(Map("request" -> request()))

  val users = Option(System.getProperty("users")).getOrElse("3").toInt
  val timeInMinutes = Option(System.getProperty("time")).getOrElse("300").toInt
  val token = Option(System.getProperty("token")).getOrElse("developer")
  val meanPauseDurationMs = Option(System.getProperty("time")).getOrElse("10").toInt

  val baseUrlString = Option(System.getProperty("baseurls")).getOrElse("https://testcloud01.humio.com")

  val baseUrls = baseUrlString.split(",").toList

  println(s"configured users=$users")
  println(s"configured time=$timeInMinutes minutes")
  println(s"token=$token")
  println(s"baseurls=${baseUrlString}  (Comma-separated)")
  println(s"dataspaces=$dataspaces")
  println(s"bulksize=$eventsPerBulk")
  println(s"template=$templateFile")
  println(s"meanPauseDurationMs=$meanPauseDurationMs")

  // poisson arrivals
  val realSampler = new RealSampler(new UniformRealDistribution(), 0, 1)

  def nextArrival = {
    (-Math.log(1.0 - realSampler.sampleDistribution) / (1 / meanPauseDurationMs)).toLong
  }

  override def before(step: => Unit): Unit = {
    super.before(step)
  }

  val httpConf = http
    .baseUrls(baseUrls) // Here is the root for all relative URLs
    .contentTypeHeader("text/plain; charset=utf-8")
    .acceptHeader("application/json") // Here are the common headers
    .header("Content-Encoding", "gzip") // Matches the processRequestBody(gzipBody)
    .acceptEncodingHeader("*") // "*" or "gzip" or "deflate" or "compress" or "identity"
    .userAgentHeader("gatling client")
    .authorizationHeader(s"Bearer ${token}")

  val scn = scenario("HEC ingestion") // A scenario is a chain of requests and pauses
    .during(timeInMinutes minutes) {
      feed(dataspaceFeeder).feed(requestFeeder)
        .exec(http("request_1")
          .post("/api/v1/ingest/hec")
          .body(StringBody("${request}"))
          .processRequestBody(gzipBody)
          .check(status.is(200))
        )
    }

  setUp(
    scn.inject(
      rampUsers(users) during(5 seconds)
    )
      .customPauses(nextArrival)
      .protocols(httpConf)
  )
}