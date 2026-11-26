package services.matches.objects

import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.deriveCodec

final case class AthleteId(value: Int)

object AthleteId:
  given Codec[AthleteId] = Codec.from(Decoder.decodeInt.map(AthleteId.apply), Encoder.encodeInt.contramap(_.value))
  given KeyEncoder[AthleteId] = KeyEncoder.instance(_.value.toString)
  given KeyDecoder[AthleteId] = KeyDecoder.instance(value => value.toIntOption.map(AthleteId.apply))

enum Side:
  case Home, Away

object Side:
  given Codec[Side] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown Side: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

enum AthleteRole:
  case Chaser, Keeper, Beater, Seeker

object AthleteRole:
  given Codec[AthleteRole] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown AthleteRole: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

final case class Athlete(id: AthleteId, name: String, side: Side, ratings: AthleteRatings)

object Athlete:
  given Codec.AsObject[Athlete] = deriveCodec[Athlete]
