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

final case class Rating(value: Double):
  require(value.isFinite && value >= 0 && value <= 100, "Rating must be between 0 and 100")

object Rating:
  given Codec[Rating] = Codec.from(
    Decoder.decodeDouble.emap(value => Either.cond(value.isFinite && value >= 0 && value <= 100, value, "Invalid Rating")).map(Rating.apply),
    Encoder.encodeDouble.contramap(_.value)
  )

final case class Stamina(value: Double):
  require(value.isFinite && value >= 0 && value <= 100, "Stamina must be between 0 and 100")

object Stamina:
  given Codec[Stamina] = Codec.from(
    Decoder.decodeDouble.emap(value => Either.cond(value.isFinite && value >= 0 && value <= 100, value, "Invalid Stamina")).map(Stamina.apply),
    Encoder.encodeDouble.contramap(_.value)
  )

final case class Stability(value: Double):
  require(value.isFinite && value >= 0 && value <= 100, "Stability must be between 0 and 100")

object Stability:
  given Codec[Stability] = Codec.from(
    Decoder.decodeDouble.emap(value => Either.cond(value.isFinite && value >= 0 && value <= 100, value, "Invalid Stability")).map(Stability.apply),
    Encoder.encodeDouble.contramap(_.value)
  )

final case class FlightRatings(speed: Rating, acceleration: Rating, turning: Rating, climb: Rating)

object FlightRatings:
  given Codec.AsObject[FlightRatings] = deriveCodec[FlightRatings]

final case class PhysicalRatings(strength: Rating, balance: Rating, endurance: Rating)

object PhysicalRatings:
  given Codec.AsObject[PhysicalRatings] = deriveCodec[PhysicalRatings]

final case class BallControlRatings(catching: Rating, carryFlight: Rating, shielding: Rating)

object BallControlRatings:
  given Codec.AsObject[BallControlRatings] = deriveCodec[BallControlRatings]

final case class ThrowingRatings(shortPass: Rating, longPass: Rating, finishing: Rating, power: Rating, curve: Rating)

object ThrowingRatings:
  given Codec.AsObject[ThrowingRatings] = deriveCodec[ThrowingRatings]

final case class AwarenessRatings(offense: Rating, defense: Rating, creativity: Rating, interception: Rating)

object AwarenessRatings:
  given Codec.AsObject[AwarenessRatings] = deriveCodec[AwarenessRatings]

final case class GoalkeepingRatings(positioning: Rating, reflexes: Rating, reach: Rating, handling: Rating)

object GoalkeepingRatings:
  given Codec.AsObject[GoalkeepingRatings] = deriveCodec[GoalkeepingRatings]

final case class AthleteRatings(
  flight: FlightRatings,
  physical: PhysicalRatings,
  ballControl: BallControlRatings,
  throwing: ThrowingRatings,
  awareness: AwarenessRatings,
  goalkeeping: GoalkeepingRatings
)

object AthleteRatings:
  given Codec.AsObject[AthleteRatings] = deriveCodec[AthleteRatings]

final case class Athlete(id: AthleteId, name: String, side: Side, ratings: AthleteRatings)

object Athlete:
  given Codec.AsObject[Athlete] = deriveCodec[Athlete]

enum MovementMode:
  case Normal, Sprinting, Bracing

object MovementMode:
  given Codec[MovementMode] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown MovementMode: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

final case class FlightState(position: Position, velocity: Velocity, facing: Direction)

object FlightState:
  given Codec.AsObject[FlightState] = deriveCodec[FlightState]

final case class AthleteState(flight: FlightState, stamina: Stamina, stability: Stability, movement: MovementMode)

object AthleteState:
  given Codec.AsObject[AthleteState] = deriveCodec[AthleteState]
