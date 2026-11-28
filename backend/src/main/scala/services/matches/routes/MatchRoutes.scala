package services.matches.routes

import cats.effect.IO
import com.sun.net.httpserver.HttpExchange
import io.circe.Json
import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import services.matches.api.*
import services.matches.logic.MatchSession
import services.matches.objects.{ActionRejection, Control, MatchId, PlayerId, SimulationTick}
import services.matches.objects.apiTypes.MatchSelection
import services.system.errorJson

private[matches] final class MatchRoutes(session: MatchSession, computerOpponent: Boolean = true):
  def route(exchange: HttpExchange, username: Option[String], connection: Connection): IO[(Int, Json)] =
    val path = exchange.getRequestURI.getPath.stripSuffix("/")
    (exchange.getRequestMethod, path) match
      case ("GET", "/health") => IO.pure(200 -> Json.obj("status" -> "ok".asJson))
      case ("GET", "/matches/demo") =>
        GetMatchSnapshotMessage(session).plan(connection).map(frame => 200 -> frame.asJson)
      case ("GET", "/matches/lobby") =>
        GetLobbyMessage(session).plan(connection).map(lobby => 200 -> lobby.asJson)
      case ("POST", "/matches/demo/start") =>
        withBody(exchange, 4096) { body =>
          val selection = if body.isEmpty then Right(None)
            else decode[MatchSelection](body).left.map(_ => "Invalid match selection").map(Some(_))
          StartMatchMessage(selection, username, session, computerOpponent).plan(connection).map {
            case Right(frame) => 200 -> frame.asJson
            case Left("match_unavailable") => 403 -> errorJson("match_unavailable")
            case Left(reason: String) => 400 -> errorJson(reason)
            case Left(rejection: ActionRejection) => 409 -> rejection.asJson
          }
        }
      case ("POST", "/matches/demo/end") =>
        withBody(exchange, 1024) { body =>
          (username, parse(body).flatMap(_.hcursor.get[MatchId]("id"))) match
            case (None, _) => IO.pure(401 -> errorJson("authentication_required"))
            case (_, Left(_)) => IO.pure(400 -> errorJson("Invalid match ID"))
            case (Some(user), Right(id)) => EndMatchMessage(user, id, session).plan(connection).map {
              case Right(frame) => 200 -> frame.asJson
              case Left(reason: String) => 403 -> errorJson(reason)
              case Left(rejection: ActionRejection) => 409 -> rejection.asJson
            }
        }
      case ("POST", "/matches/demo/controls") =>
        withBody(exchange, 8192) { body =>
          val command = for
            json <- parse(body)
            player <- json.hcursor.get[PlayerId]("player")
            control <- json.hcursor.get[Control]("control")
          yield ApplyControlMessage(player, control, username, session)
          command match
            case Left(error) => IO.pure(400 -> errorJson(error.getMessage))
            case Right(message) => message.plan(connection).map {
              case Right(tick) => 202 -> Json.obj("scheduledTick" -> tick.asJson)
              case Left(reason: String) => 403 -> errorJson(reason)
              case Left(rejection: ActionRejection) => 409 -> rejection.asJson
            }
        }
      case ("GET", history) if history.startsWith("/matches/demo/timeline/") =>
        readFrame(history.stripPrefix("/matches/demo/timeline/"), None, connection)
      case ("GET", "/matches/history") =>
        ListMatchHistoryMessage().plan(connection).map(matches => 200 -> matches.asJson)
      case ("GET", history) if history.startsWith("/matches/history/") =>
        history.stripPrefix("/matches/history/").split("/").toList match
          case id :: "timeline" :: tick :: Nil => readFrame(tick, Some(MatchId(id)), connection)
          case _ => IO.pure(404 -> errorJson("Route not found"))
      case _ => IO.pure(404 -> errorJson("Route not found"))

  private def withBody(exchange: HttpExchange, limit: Int)(handle: String => IO[(Int, Json)]): IO[(Int, Json)] =
    IO.blocking(exchange.getRequestBody.readNBytes(limit + 1)).flatMap { body =>
      if body.length > limit then IO.pure(413 -> errorJson("Request body is too large"))
      else handle(new String(body, UTF_8))
    }

  private def readFrame(tick: String, id: Option[MatchId], connection: Connection): IO[(Int, Json)] =
    val missing = 404 -> errorJson("Timeline frame not found")
    tick.toLongOption.filter(_ >= 0) match
      case None => IO.pure(missing)
      case Some(value) =>
        GetMatchFrameMessage(SimulationTick(value), id, session).plan(connection).map {
          case Some(frame) => 200 -> frame.asJson
          case None => missing
        }
