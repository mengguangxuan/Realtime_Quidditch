package services.matches.objects

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

final case class Rating(value: Double):
  require(value.isFinite && value >= 0 && value <= 100, "Rating must be between 0 and 100")

object Rating:
  given Codec[Rating] = Codec.from(
    Decoder.decodeDouble.emap(value => Either.cond(value.isFinite && value >= 0 && value <= 100, value, "Invalid Rating")).map(Rating.apply),
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
