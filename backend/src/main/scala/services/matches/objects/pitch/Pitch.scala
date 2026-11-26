package services.matches.objects

import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.deriveCodec

final case class Vec3(x: Double, y: Double, z: Double):
  def +(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
  def -(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
  def *(factor: Double): Vec3 = Vec3(x * factor, y * factor, z * factor)
  def dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z
  def cross(other: Vec3): Vec3 =
    Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
  def length: Double = math.hypot(math.hypot(x, y), z)

object Vec3:
  given Codec.AsObject[Vec3] = deriveCodec[Vec3]
  val zero: Vec3 = Vec3(0, 0, 0)
  val up: Vec3 = Vec3(0, 0, 1)

final case class Position(value: Vec3)

object Position:
  given Codec.AsObject[Position] = deriveCodec[Position]

final case class Velocity(value: Vec3)

object Velocity:
  given Codec.AsObject[Velocity] = deriveCodec[Velocity]

final case class Direction private (value: Vec3):
  require(math.abs(value.length - 1) < 1e-8)

object Direction:
  def from(value: Vec3): Either[String, Direction] =
    val length = value.length
    Either.cond(length.isFinite && length > 1e-9, length, "Direction needs a finite, nonzero vector")
      .map(size => Direction(value * (1 / size)))

  val east: Direction = Direction(Vec3(1, 0, 0))
  val west: Direction = Direction(Vec3(-1, 0, 0))
  given Codec[Direction] = Codec.from(
    Decoder[Vec3].emap(from),
    Encoder[Vec3].contramap(_.value)
  )

final case class GoalRing(y: Double, height: Double, innerRadius: Double)

final case class Pitch(
  halfLength: Double,
  halfWidth: Double,
  ceiling: Double,
  goalPlaneX: Double,
  rings: List[GoalRing]
)

object Pitch:
  val alpine: Pitch = Pitch(53, 27, 25, 44,
    List(GoalRing(-7.1, 8.4, 1.735), GoalRing(0, 12, 1.895), GoalRing(7.1, 10, 1.655)))
