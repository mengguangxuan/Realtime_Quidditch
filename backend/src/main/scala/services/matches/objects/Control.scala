package services.matches.objects

import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.deriveCodec

enum ControlKind:
  case Fly, Brace, Catch, Shoot, Charge, Grab, StrikeBludger

object ControlKind:
  given Codec[ControlKind] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown ControlKind: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

final case class ControlAssignment(player: PlayerId, athlete: AthleteId, allowedControls: Set[ControlKind])

object ControlAssignment:
  given Codec.AsObject[ControlAssignment] = deriveCodec[ControlAssignment]

final case class AxisInput(value: Double):
  require(value.isFinite && value >= -1 && value <= 1, "AxisInput must be between -1 and 1")

object AxisInput:
  given Codec[AxisInput] = Codec.from(
    Decoder.decodeDouble.emap(value => Either.cond(value.isFinite && value >= -1 && value <= 1, value, "Invalid AxisInput")).map(AxisInput.apply),
    Encoder.encodeDouble.contramap(_.value)
  )

final case class ShotPower(value: Double):
  require(value.isFinite && value >= 0 && value <= 1, "ShotPower must be between 0 and 1")

object ShotPower:
  given Codec[ShotPower] = Codec.from(
    Decoder.decodeDouble.emap(value => Either.cond(value.isFinite && value >= 0 && value <= 1, value, "Invalid ShotPower")).map(ShotPower.apply),
    Encoder.encodeDouble.contramap(_.value)
  )

// A turn axis selects rider-relative movement. Existing world-space callers omit it.
final case class FlightInput(forward: AxisInput, strafe: AxisInput, climb: AxisInput, sprint: Boolean,
    turn: Option[AxisInput] = None)

object FlightInput:
  given Codec.AsObject[FlightInput] = deriveCodec[FlightInput]

final case class ShotAim(direction: Direction, power: ShotPower, spin: Option[AxisInput])

object ShotAim:
  given Codec.AsObject[ShotAim] = deriveCodec[ShotAim]

enum CatchTarget:
  case Quaffle, Snitch

object CatchTarget:
  given Codec[CatchTarget] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown CatchTarget: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

enum Control(val kind: ControlKind):
  case Fly(input: FlightInput) extends Control(ControlKind.Fly)
  case Brace(active: Boolean) extends Control(ControlKind.Brace)
  case Catch(target: CatchTarget) extends Control(ControlKind.Catch)
  case Shoot(aim: ShotAim) extends Control(ControlKind.Shoot)
  case Charge extends Control(ControlKind.Charge)
  case Grab extends Control(ControlKind.Grab)
  case StrikeBludger(ball: BludgerId, direction: Direction) extends Control(ControlKind.StrikeBludger)

object Control:
  given Codec.AsObject[Control] = deriveCodec[Control]

enum ActionRejection:
  case MatchNotInPlay
  case PlayerNotInMatch
  case PlayerIsComputer
  case AthleteNotOnPlayersSide
  case AthleteControlledByAnotherPlayer
  case NotBallHolder
  case TemporarilyUnavailable
  case ControlNotAllowed(kind: ControlKind)
  case BallUnavailable
  case RoleNotEligible

object ActionRejection:
  given Codec.AsObject[ActionRejection] = deriveCodec[ActionRejection]
