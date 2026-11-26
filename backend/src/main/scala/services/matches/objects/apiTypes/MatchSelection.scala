package services.matches.objects.apiTypes

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import services.matches.objects.{AthleteId, TeamTactics}

final case class MatchSelection(teamSize: Int, watch: Boolean, athlete: AthleteId, tactics: Option[TeamTactics] = None)

object MatchSelection:
  given Codec.AsObject[MatchSelection] = deriveCodec[MatchSelection]
