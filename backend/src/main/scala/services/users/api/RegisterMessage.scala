package services.users.api

import cats.effect.IO
import java.sql.Connection
import services.system.api.APIMessage
import services.users.tables.account.AccountTable
import services.users.utils.*

final case class RegisterMessage(username: String, password: String) extends APIMessage[Either[String, Unit]]:
  override def plan(connection: Connection): IO[Either[String, Unit]] =
    val name = normalizeUsername(username)
    if !validCredentials(name, password) then IO.pure(Left("invalid_input"))
    else
      for
        salt <- IO.delay(randomBytes(16))
        hash <- IO.blocking(passwordDigest(password, salt))
        inserted <- AccountTable.insert(connection, name, salt, hash)
      yield Either.cond(inserted, (), "username_taken")
