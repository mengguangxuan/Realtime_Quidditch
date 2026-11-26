package services.matches.api

import cats.effect.IO
import java.sql.Connection
import services.matches.objects.MatchId
import services.matches.tables.timeline.TimelineTable
import services.system.api.NoRequestMessage

final case class ListMatchHistoryMessage() extends NoRequestMessage[List[MatchId]]:
  override def plan(connection: Connection): IO[List[MatchId]] =
    TimelineTable.listMatches(connection)
