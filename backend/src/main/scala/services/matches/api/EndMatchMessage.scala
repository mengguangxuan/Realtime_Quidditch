package services.matches.api

import cats.effect.IO
import java.sql.Connection
import services.matches.logic.MatchSession
import services.matches.objects.{ActionRejection, MatchId, TimelineFrame}
import services.system.api.APIMessage

final case class EndMatchMessage(username: String, id: MatchId, private val session: MatchSession)
    extends APIMessage[Either[String | ActionRejection, TimelineFrame]]:
  override def plan(connection: Connection): IO[Either[String | ActionRejection, TimelineFrame]] =
    IO.blocking(session.endFor(username, id))
