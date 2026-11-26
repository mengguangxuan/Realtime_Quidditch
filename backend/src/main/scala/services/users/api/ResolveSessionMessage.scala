package services.users.api

import cats.effect.IO
import java.sql.Connection
import java.time.Instant
import scala.collection.mutable
import services.system.api.APIMessage

final case class ResolveSessionMessage(token: String,
    private val sessions: mutable.Map[String, (String, Instant)]) extends APIMessage[Option[String]]:
  override def plan(connection: Connection): IO[Option[String]] =
    IO.delay(sessions.synchronized(sessions.get(token).filter(_._2.isAfter(Instant.now())).map(_._1)))
