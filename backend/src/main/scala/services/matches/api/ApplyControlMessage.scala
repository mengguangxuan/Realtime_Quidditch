package services.matches.api

import cats.effect.IO
import java.sql.Connection
import services.matches.logic.MatchSession
import services.matches.objects.*
import services.system.api.APIMessage

final case class ApplyControlMessage(player: PlayerId, control: Control, username: Option[String],
    private val session: MatchSession) extends APIMessage[Either[String | ActionRejection, SimulationTick]]:
  override def plan(connection: Connection): IO[Either[String | ActionRejection, SimulationTick]] =
    IO.delay {
      username match
        case Some(name) => session.submitFor(name, control)
        case None => session.submit(player, control)
    }
