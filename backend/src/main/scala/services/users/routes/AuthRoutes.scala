package services.users.routes

import cats.effect.IO
import com.sun.net.httpserver.HttpExchange
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Instant
import scala.collection.mutable
import services.system.errorJson
import services.users.api.{LoginMessage, LogoutMessage, RegisterMessage}

private[users] final class AuthRoutes(sessions: mutable.Map[String, (String, Instant)]):
  def route(exchange: HttpExchange, connection: Connection): IO[(Int, Json)] =
    val path = exchange.getRequestURI.getPath.stripSuffix("/")
    (exchange.getRequestMethod, path) match
      case ("POST", "/auth/register") => credentials(exchange) { (username, password) =>
        RegisterMessage(username, password).plan(connection).map {
          case Right(_) => 201 -> Json.obj()
          case Left("username_taken") => 409 -> errorJson("username_taken")
          case Left(reason) => 400 -> errorJson(reason)
        }
      }
      case ("POST", "/auth/login") => credentials(exchange) { (username, password) =>
        LoginMessage(username, password, sessions).plan(connection).map {
          case Right(value) => 200 -> Json.obj("token" -> Json.fromString(value))
          case Left("invalid_credentials") => 401 -> errorJson("invalid_credentials")
          case Left(reason) => 400 -> errorJson(reason)
        }
      }
      case ("POST", "/auth/logout") =>
        LogoutMessage(services.system.bearerToken(exchange), sessions).plan(connection).as(200 -> Json.obj())
      case _ => IO.pure(404 -> errorJson("not_found"))

  private def credentials(exchange: HttpExchange)(
      handle: (String, String) => IO[(Int, Json)]
  ): IO[(Int, Json)] =
    IO.blocking(exchange.getRequestBody.readNBytes(4097)).flatMap { body =>
      if body.length > 4096 then IO.pure(413 -> errorJson("request_too_large"))
      else
        val fields = for
          json <- parse(new String(body, UTF_8))
          username <- json.hcursor.get[String]("username")
          password <- json.hcursor.get[String]("password")
        yield (username, password)
        fields.fold(_ => IO.pure(400 -> errorJson("invalid_input")), (username, password) => handle(username, password))
    }
