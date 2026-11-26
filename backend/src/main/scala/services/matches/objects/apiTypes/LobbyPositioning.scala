package services.matches.objects.apiTypes

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import services.matches.objects.Position

final case class LobbyPositioning(slots: List[Position], halfLength: Double, halfWidth: Double,
    goalPlaneX: Double, clearance: Double, spacing: Double)

object LobbyPositioning:
  given Codec.AsObject[LobbyPositioning] = deriveCodec[LobbyPositioning]
