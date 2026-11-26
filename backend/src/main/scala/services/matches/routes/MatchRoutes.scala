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
import services.matches.logic.{MatchSession, MatchSimulation}
import services.matches.objects.{MatchId, MatchPhase, Pitch, PlayerId, PlayerKind, Side, SimulationTick, TeamTactics}
import services.matches.objects.apiTypes.MatchSelection
import services.matches.tables.athletecard.AthleteCardTable
import services.matches.tables.timeline.TimelineTable
import services.system.Database

final class MatchRoutes(session: MatchSession, database: Option[Path] = None, localControlsOnly: Boolean = false,
    computerOpponent: Boolean = true)
    extends HttpHandler:
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
      case ("GET", "/matches/lobby") if database.nonEmpty =>
        val state = session.latest.state
        val setup = state.setup
        val watch = setup.players.forall(_.kind == PlayerKind.Computer)
        val available = cards
        val slots = available.filter(_.side == Side.Home).indices.map(index => MatchSimulation.startingSlot(Side.Home, index))
        val pitch = Pitch.alpine
        val positioning = Json.obj("slots" -> slots.asJson, "halfLength" -> pitch.halfLength.asJson,
          "halfWidth" -> pitch.halfWidth.asJson, "goalPlaneX" -> pitch.goalPlaneX.asJson,
          "clearance" -> MatchSimulation.startingClearance.asJson, "spacing" -> MatchSimulation.startingSpacing.asJson)
        200 -> Json.obj("cards" -> available.asJson, "teamSize" -> (setup.roster.size / 2).asJson,
          "watch" -> watch.asJson, "active" -> (state.phase != MatchPhase.Ready).asJson,
          "homeTactics" -> setup.tacticsFor(Side.Home).asJson, "positioning" -> positioning)
      case ("POST", "/matches/demo/start") => start(exchange)
      case ("POST", "/matches/demo/controls") =>
        val body = exchange.getRequestBody.readNBytes(8193)
        if body.length > 8192 then 413 -> error("Request body is too large")
        else
          val message = try decode[ApplyControlMessage](new String(body, UTF_8)).left.map(_.getMessage)
            catch case NonFatal(_) => Left("Invalid control data")
          message match
            case Left(detail) => 400 -> error(detail)
            case Right(command) =>
              val local = if localControlsOnly then command.copy(player = PlayerId("home"))
                else command
              local.plan(session).fold(
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

  private def cards = database.toList.flatMap(path =>
    Database.connect(path).use(AthleteCardTable.list).unsafeRunSync())

  private def start(exchange: HttpExchange): (Int, Json) =
    val body = exchange.getRequestBody.readNBytes(4097)
    if body.length > 4096 then return 413 -> error("Request body is too large")
    val lineup =
      if body.isEmpty then Right(None)
      else decode[MatchSelection](new String(body, UTF_8)).left.map(_ => "Invalid match selection")
        .flatMap(choice => MatchSession.demoSetup(cards, computerOpponent || choice.watch, choice.watch,
          choice.teamSize, Some(choice.athlete), choice.tactics.getOrElse(TeamTactics.default),
          choice.startingPositions)).map(Some(_))
    lineup.fold(detail => 400 -> error(detail),
      setup => session.start(setup).fold(rejection => 409 -> rejection.asJson, view => 200 -> view.asJson))

  private def error(detail: String): Json = Json.obj("error" -> detail.asJson)
