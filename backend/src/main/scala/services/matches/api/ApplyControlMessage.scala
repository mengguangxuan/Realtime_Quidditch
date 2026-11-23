package services.matches.api

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import services.matches.logic.MatchSession
import services.matches.objects.*

final case class ApplyControlMessage(player: PlayerId, control: Control):
  def plan(session: MatchSession): Either[ActionRejection, SimulationTick] =
    session.submit(player, control)

object ApplyControlMessage:
  given Codec.AsObject[ApplyControlMessage] = deriveCodec[ApplyControlMessage]
