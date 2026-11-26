package services.matches.api

import cats.effect.IO
import java.sql.Connection
import services.matches.logic.MatchSession
import services.matches.objects.{MatchId, SimulationTick, TimelineFrame}
import services.matches.tables.timeline.TimelineTable
import services.system.api.APIMessage

final case class GetMatchFrameMessage(tick: SimulationTick, matchId: Option[MatchId],
    private val session: MatchSession) extends APIMessage[Option[TimelineFrame]]:
  override def plan(connection: Connection): IO[Option[TimelineFrame]] =
    matchId match
      case Some(id) => TimelineTable.find(connection, id, tick)
      case None => IO.delay(session.frame(tick))
