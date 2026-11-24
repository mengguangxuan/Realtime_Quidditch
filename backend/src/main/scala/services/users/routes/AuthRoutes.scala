package services.users.routes

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpHandler}
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration, Instant}
import java.util.{Base64, Locale}
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import scala.collection.mutable
import scala.util.control.NonFatal
import services.system.Database
import services.users.tables.account.AccountTable

final class AuthRoutes(database: Path, matches: HttpHandler) extends HttpHandler:
  private val random = SecureRandom()
  private val sessions = mutable.Map.empty[String, (String, Instant)]
  private var matchOwner = Option.empty[String]

  override def handle(exchange: HttpExchange): Unit =
    val path = exchange.getRequestURI.getPath.stripSuffix("/")
    val method = exchange.getRequestMethod
    val token = Option(exchange.getRequestHeaders.getFirst("Authorization"))
      .filter(_.startsWith("Bearer ")).map(_.drop(7))
    try
      (method, path) match
        case ("POST", "/auth/register") => credentialsRequest(exchange, register = true)
        case ("POST", "/auth/login") => credentialsRequest(exchange, register = false)
        case ("POST", "/auth/logout") =>
          synchronized { token.foreach(sessions.remove) }
          respond(exchange, 200, Json.obj())
        case ("GET", "/health") => matches.handle(exchange)
        case (_, p) if p == "/matches" || p.startsWith("/matches/") =>
          token.flatMap(authenticatedUser) match
            case None => error(exchange, 401, "authentication_required")
            case Some(username) =>
              val start = method == "POST" && p == "/matches/demo/start"
              val controls = method == "POST" && p == "/matches/demo/controls"
              if start then startMatch(exchange, username)
              else if controls && !ownsMatch(username) then error(exchange, 403, "match_unavailable")
              else matches.handle(exchange)
        case _ => error(exchange, 404, "not_found")
    catch
      case NonFatal(exception) =>
        System.err.println(s"HTTP request failed: ${exception.getClass.getSimpleName}")
        error(exchange, 500, "server_error")
    finally exchange.close()

  private def credentialsRequest(exchange: HttpExchange, register: Boolean): Unit =
    val body = exchange.getRequestBody.readNBytes(4097)
    if body.length > 4096 then error(exchange, 413, "request_too_large")
    else
      val credentials = for
        json <- parse(new String(body, UTF_8))
        username <- json.hcursor.get[String]("username")
        password <- json.hcursor.get[String]("password")
      yield (username.trim.toLowerCase(Locale.ROOT), password)
      credentials match
        case Right((username, password)) if username.matches("[a-z0-9_]{3,24}") &&
            password.length >= 8 && password.length <= 128 =>
          if register then
            val salt = randomBytes(16)
            val hash = digest(password, salt)
            val created = Database.connect(database).use(AccountTable.insert(_, username, salt, hash)).unsafeRunSync()
            if created then respond(exchange, 201, Json.obj())
            else error(exchange, 409, "username_taken")
          else
            val stored = Database.connect(database).use(AccountTable.credentials(_, username)).unsafeRunSync()
            val salt = stored.fold(new Array[Byte](16))(_._1)
            val candidate = digest(password, salt)
            if stored.exists((_, hash) => MessageDigest.isEqual(candidate, hash)) then
              respond(exchange, 200, Json.obj("token" -> Json.fromString(issue(username))))
            else error(exchange, 401, "invalid_credentials")
        case _ => error(exchange, 400, "invalid_input")

  private def digest(password: String, salt: Array[Byte]): Array[Byte] =
    val spec = PBEKeySpec(password.toCharArray, salt, 600000, 256)
    try SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded
    finally spec.clearPassword()

  private def randomBytes(size: Int): Array[Byte] =
    val bytes = new Array[Byte](size)
    random.nextBytes(bytes)
    bytes

  private def issue(username: String): String = synchronized {
    val now = Instant.now()
    sessions.filterInPlace { case (_, (owner, expiration)) => owner != username && expiration.isAfter(now) }
    val token = Base64.getUrlEncoder.withoutPadding().encodeToString(randomBytes(32))
    sessions(token) = username -> now.plus(Duration.ofHours(8))
    token
  }

  private def authenticatedUser(token: String): Option[String] = synchronized {
    sessions.get(token).filter(_._2.isAfter(Instant.now())).map(_._1)
  }

  private def ownsMatch(username: String): Boolean = synchronized {
    matchOwner.contains(username)
  }

  private def startMatch(exchange: HttpExchange, username: String): Unit = synchronized {
    if matchOwner.exists(_ != username) then error(exchange, 403, "match_unavailable")
    else
      matches.handle(exchange)
      if exchange.getResponseCode == 200 then matchOwner = Some(username)
  }

  private def error(exchange: HttpExchange, status: Int, code: String): Unit =
    respond(exchange, status, Json.obj("error" -> Json.fromString(code)))

  private def respond(exchange: HttpExchange, status: Int, json: Json): Unit =
    val bytes = json.noSpaces.getBytes(UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.getResponseHeaders.set("Cache-Control", "no-store")
    exchange.sendResponseHeaders(status, bytes.length)
    exchange.getResponseBody.write(bytes)
