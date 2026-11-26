package services.matches.objects

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

final case class PlayerId(value: String)

object PlayerId:
  given Codec[PlayerId] = Codec.from(Decoder.decodeString.map(PlayerId.apply), Encoder.encodeString.contramap(_.value))

enum PlayerKind:
  case Human, Computer

object PlayerKind:
  given Codec[PlayerKind] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown player kind: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

final case class Player(id: PlayerId, side: Side, kind: PlayerKind)

object Player:
  given Codec.AsObject[Player] = deriveCodec[Player]
