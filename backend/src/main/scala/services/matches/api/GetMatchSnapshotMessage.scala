package services.matches.api

import cats.effect.IO
import java.sql.Connection
import services.matches.logic.MatchSession
import services.matches.objects.TimelineFrame
import services.system.api.NoRequestMessage

final case class GetMatchSnapshotMessage(private val session: MatchSession) extends NoRequestMessage[TimelineFrame]:
  override def plan(connection: Connection): IO[TimelineFrame] =
    IO.delay(session.latest)
