package services.matches.api

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import services.matches.objects.AthleteId

final case class MatchSelection(teamSize: Int, watch: Boolean, athlete: AthleteId)

object MatchSelection:
  given Codec.AsObject[MatchSelection] = deriveCodec[MatchSelection]
