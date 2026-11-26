package services.matches.objects

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

enum ContactKind:
  case Incidental, Charge, Grab

object ContactKind:
  given Codec[ContactKind] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown ContactKind: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

enum FoulKind:
  case RearCharge, ExcessiveCharge, Grabbing

object FoulKind:
  given Codec[FoulKind] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown FoulKind: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

final case class Foul(
  offender: AthleteId,
  victim: AthleteId,
  kind: FoulKind,
  position: Position,
  deniedClearChance: Boolean
)

object Foul:
  given Codec.AsObject[Foul] = deriveCodec[Foul]
