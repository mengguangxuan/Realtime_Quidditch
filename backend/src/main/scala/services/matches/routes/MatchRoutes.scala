package services.matches.routes

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpHandler}
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.util.control.NonFatal
import services.matches.api.ApplyControlMessage
import services.matches.logic.MatchSession
import services.matches.objects.{MatchId, SimulationTick}
import services.matches.tables.timeline.TimelineTable
import services.system.Database

final class MatchRoutes(session: MatchSession, database: Option[Path] = None) extends HttpHandler:
  override def handle(exchange: HttpExchange): Unit =
    try
      val (status, json) = try route(exchange)
        catch case NonFatal(exception) =>
          System.err.println(s"Request failed: ${exception.getMessage}")
          500 -> error("Could not complete the request")
      val bytes = json.noSpaces.getBytes(UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
    finally exchange.close()

  private def route(exchange: HttpExchange): (Int, Json) =
    val path = exchange.getRequestURI.getPath.stripSuffix("/")
    (exchange.getRequestMethod, path) match
      case ("GET", "/health") => 200 -> Json.obj("status" -> "ok".asJson)
      case ("GET", "/matches/demo") => 200 -> session.latest.asJson
      case ("POST", "/matches/demo/start") =>
        session.start().fold(error => 409 -> error.asJson, view => 200 -> view.asJson)
      case ("POST", "/matches/demo/controls") =>
        val body = exchange.getRequestBody.readNBytes(8193)
        if body.length > 8192 then 413 -> error("Request body is too large")
        else
          val message = try decode[ApplyControlMessage](new String(body, UTF_8)).left.map(_.getMessage)
            catch case NonFatal(_) => Left("Invalid control data")
          message match
            case Left(detail) => 400 -> error(detail)
            case Right(command) =>
              command.plan(session).fold(
                rejection => 409 -> rejection.asJson,
                tick => 202 -> Json.obj("scheduledTick" -> tick.asJson)
              )
      case ("GET", history) if history.startsWith("/matches/demo/timeline/") =>
        val tick = history.stripPrefix("/matches/demo/timeline/").toLongOption
        tick.flatMap(value => session.frame(SimulationTick(value)))
          .fold(404 -> error("Timeline frame not found"))(view => 200 -> view.asJson)
      case ("GET", "/matches/history") if database.nonEmpty =>
        val matches = database.toList.flatMap(path =>
          Database.connect(path).use(TimelineTable.listMatches).unsafeRunSync())
        200 -> matches.asJson
      case ("GET", history) if history.startsWith("/matches/history/") =>
        history.stripPrefix("/matches/history/").split("/").toList match
          case id :: "timeline" :: tick :: Nil =>
            val frame = for
              path <- database
              number <- tick.toLongOption.filter(_ >= 0)
              saved <- Database.connect(path).use(TimelineTable.find(_, MatchId(id), SimulationTick(number)))
                .unsafeRunSync()
            yield saved
            frame.fold(404 -> error("Timeline frame not found"))(view => 200 -> view.asJson)
          case _ => 404 -> error("Route not found")
      case _ => 404 -> error("Route not found")

  private def error(detail: String): Json = Json.obj("error" -> detail.asJson)
