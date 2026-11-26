package services.system

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpHandler}
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.sql.Connection
import scala.util.control.NonFatal

private[services] def errorJson(detail: String): Json =
  Json.obj("error" -> Json.fromString(detail))

private[services] def bearerToken(exchange: HttpExchange): Option[String] =
  Option(exchange.getRequestHeaders.getFirst("Authorization")).filter(_.startsWith("Bearer ")).map(_.drop(7))

private[system] def applicationHandler(database: Path,
    users: (HttpExchange, Connection) => IO[(Int, Json)],
    resolveUser: (String, Connection) => IO[Option[String]],
    matches: (HttpExchange, Option[String], Connection) => IO[(Int, Json)]): HttpHandler =
  databaseHandler(database) { (exchange, connection) =>
    val path = exchange.getRequestURI.getPath.stripSuffix("/")
    if path == "/health" && exchange.getRequestMethod == "GET" then matches(exchange, None, connection)
    else if path == "/auth" || path.startsWith("/auth/") then users(exchange, connection)
    else if path == "/matches" || path.startsWith("/matches/") then
      val username = bearerToken(exchange).fold(IO.pure(Option.empty[String]))(resolveUser(_, connection))
      username.flatMap {
        case Some(value) => matches(exchange, Some(value), connection)
        case None => IO.pure(401 -> errorJson("authentication_required"))
      }
    else IO.pure(404 -> errorJson("not_found"))
  }

private[services] def databaseHandler(database: Path)(
    route: (HttpExchange, Connection) => IO[(Int, Json)]
): HttpHandler = exchange =>
  try
    val (status, response) =
      try Database.connect(database).use(connection => route(exchange, connection)).unsafeRunSync()
      catch case NonFatal(exception) =>
        System.err.println(s"HTTP request failed: ${exception.getClass.getSimpleName}")
        500 -> errorJson("server_error")
    val body = response.noSpaces.getBytes(UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.getResponseHeaders.set("Cache-Control", "no-store")
    exchange.sendResponseHeaders(status, body.length.toLong)
    exchange.getResponseBody.write(body)
  finally exchange.close()
