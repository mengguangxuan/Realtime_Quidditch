package services.matches.objects

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

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
