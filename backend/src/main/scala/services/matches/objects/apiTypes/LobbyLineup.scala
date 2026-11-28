package services.matches.objects.apiTypes

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import services.matches.objects.{Athlete, AthleteId, Position}

final case class LobbyLineup(cards: List[Athlete], slots: List[Position], keepers: List[AthleteId],
    reserves: List[Athlete])

object LobbyLineup:
  given Codec.AsObject[LobbyLineup] = deriveCodec[LobbyLineup]
