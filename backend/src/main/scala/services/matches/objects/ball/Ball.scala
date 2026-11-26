package services.matches.objects

import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.deriveCodec

final case class BallPhysics(radiusMeters: Double, massKg: Double, dragCoefficient: Double, spinLiftCoefficient: Double):
  require(radiusMeters.isFinite && radiusMeters > 0 && massKg.isFinite && massKg > 0)
  require(dragCoefficient.isFinite && dragCoefficient >= 0 && spinLiftCoefficient.isFinite && spinLiftCoefficient >= 0)

object BallPhysics:
  given Codec.AsObject[BallPhysics] = deriveCodec[BallPhysics]

final case class AngularVelocity(radiansPerSecond: Vec3)

object AngularVelocity:
  given Codec.AsObject[AngularVelocity] = deriveCodec[AngularVelocity]

final case class BallMotion(position: Position, velocity: Velocity, spin: AngularVelocity)

object BallMotion:
  given Codec.AsObject[BallMotion] = deriveCodec[BallMotion]

enum QuaffleState:
  case Free(motion: BallMotion)
  case HeldBy(athlete: AthleteId)

object QuaffleState:
  given Codec.AsObject[QuaffleState] = deriveCodec[QuaffleState]

final case class BludgerId(value: Int)

object BludgerId:
  given Codec[BludgerId] = Codec.from(Decoder.decodeInt.map(BludgerId.apply), Encoder.encodeInt.contramap(_.value))
  given KeyEncoder[BludgerId] = KeyEncoder.instance(_.value.toString)
  given KeyDecoder[BludgerId] = KeyDecoder.instance(value => value.toIntOption.map(BludgerId.apply))

final case class BludgerSpec(physics: BallPhysics, maxSpeed: Double, steeringAcceleration: Double)

object BludgerSpec:
  given Codec.AsObject[BludgerSpec] = deriveCodec[BludgerSpec]

enum BludgerState:
  case Roaming(motion: BallMotion)
  case Pursuing(motion: BallMotion, target: AthleteId)
  case Deflected(motion: BallMotion, resumePursuitAt: PlayTick)

object BludgerState:
  given Codec.AsObject[BludgerState] = deriveCodec[BludgerState]

final case class Hideout(position: Position)

object Hideout:
  given Codec.AsObject[Hideout] = deriveCodec[Hideout]

final case class SnitchSpec(
  physics: BallPhysics,
  cruiseSpeed: Double,
  escapeSpeed: Double,
  steeringAcceleration: Double,
  detectionRadius: Double,
  hideouts: List[Hideout],
  maxHideTicks: Int,
  catchPoints: Int
)

object SnitchSpec:
  given Codec.AsObject[SnitchSpec] = deriveCodec[SnitchSpec]

enum SnitchState:
  case Roaming(motion: BallMotion, destination: Position)
  case Evading(motion: BallMotion, pursuer: AthleteId)
  case SeekingCover(motion: BallMotion, hideout: Hideout)
  case Hidden(at: Hideout, revealAt: PlayTick)

object SnitchState:
  given Codec.AsObject[SnitchState] = deriveCodec[SnitchState]

final case class BallSetup(
  quaffle: BallPhysics,
  bludgers: Map[BludgerId, BludgerSpec],
  snitch: Option[SnitchSpec]
)

object BallSetup:
  given Codec.AsObject[BallSetup] = deriveCodec[BallSetup]

final case class SpecialBalls(bludgers: Map[BludgerId, BludgerState], snitch: Option[SnitchState])

object SpecialBalls:
  given Codec.AsObject[SpecialBalls] = deriveCodec[SpecialBalls]

final case class MatchBalls(quaffle: QuaffleState, special: SpecialBalls)

object MatchBalls:
  given Codec.AsObject[MatchBalls] = deriveCodec[MatchBalls]

enum QuafflePossessionOutcome:
  case Unchanged
  case Retained(carrier: AthleteId)
  case Caught(receiver: AthleteId)
  case Dropped(formerCarrier: AthleteId, motion: BallMotion)
  case Released(thrower: AthleteId, motion: BallMotion)

object QuafflePossessionOutcome:
  given Codec.AsObject[QuafflePossessionOutcome] = deriveCodec[QuafflePossessionOutcome]
