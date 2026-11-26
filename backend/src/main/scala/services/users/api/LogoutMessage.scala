package services.users.api

import cats.effect.IO
import java.sql.Connection
import java.time.Instant
import scala.collection.mutable
import services.system.api.APIMessage

final case class LogoutMessage(token: Option[String],
    private val sessions: mutable.Map[String, (String, Instant)]) extends APIMessage[Unit]:
  override def plan(connection: Connection): IO[Unit] =
    IO.delay(sessions.synchronized(token.foreach(sessions.remove)))
