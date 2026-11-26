package services.users.api

import cats.effect.IO
import java.security.MessageDigest
import java.sql.Connection
import java.time.{Duration, Instant}
import java.util.Base64
import scala.collection.mutable
import services.system.api.APIMessage
import services.users.tables.account.AccountTable
import services.users.utils.*

final case class LoginMessage(username: String, password: String,
    private val sessions: mutable.Map[String, (String, Instant)]) extends APIMessage[Either[String, String]]:
  override def plan(connection: Connection): IO[Either[String, String]] =
    val name = normalizeUsername(username)
    if !validCredentials(name, password) then IO.pure(Left("invalid_input"))
    else for
      stored <- AccountTable.credentials(connection, name)
      candidate <- IO.blocking(passwordDigest(password, stored.fold(new Array[Byte](16))(_._1)))
      result <- IO.delay {
        if stored.exists((_, hash) => MessageDigest.isEqual(candidate, hash)) then
          val token = Base64.getUrlEncoder.withoutPadding().encodeToString(randomBytes(32))
          val now = Instant.now()
          sessions.synchronized {
            sessions.filterInPlace { case (_, (owner, expiration)) => owner != name && expiration.isAfter(now) }
            sessions(token) = name -> now.plus(Duration.ofHours(8))
          }
          Right(token)
        else Left("invalid_credentials")
      }
    yield result
