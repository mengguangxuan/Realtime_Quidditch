package services.matches.objects.apiTypes

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import services.matches.objects.{AthleteId, Position, TeamTactics}

final case class MatchSelection(
  teamSize: Int,
  watch: Boolean,
  athlete: AthleteId,
  tactics: Option[TeamTactics] = None,
  startingPositions: Option[Map[AthleteId, Position]] = None,
  swapTeams: Option[Boolean] = None,
  starters: Option[List[AthleteId]] = None
)

object MatchSelection:
  given Codec.AsObject[MatchSelection] = deriveCodec[MatchSelection]
