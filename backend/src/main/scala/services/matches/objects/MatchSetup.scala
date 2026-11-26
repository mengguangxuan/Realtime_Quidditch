package services.matches.objects

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class MatchSetup(
  roster: List[Athlete],
  players: List[Player],
  roles: Map[AthleteId, AthleteRole],
  assignments: List[ControlAssignment],
  balls: BallSetup
)

object MatchSetup:
  given Codec.AsObject[MatchSetup] = deriveCodec[MatchSetup]
