package services.matches.logic

import scala.util.Random
import services.matches.objects.*
import FlightRules.clamp

private[matches] object ShotAccuracy:
  private val pitch = Pitch.alpine
  val maxDeviation = math.toRadians(6)

  def distanceToGoal(athlete: Athlete, flight: FlightState): Double =
    val goalX = if athlete.side == Side.Home then pitch.goalPlaneX else -pitch.goalPlaneX
    pitch.rings.map(ring => (Vec3(goalX, ring.y, ring.height) - flight.position.value).length).min

  def standardDeviation(athlete: Athlete, state: AthleteState, aim: ShotAim): Double =
    val flight = state.flight
    val attempt = aim.direction.value
    val horizontal = Direction.from(Vec3(attempt.x, attempt.y, 0)).toOption
    val facingCost = horizontal.fold(1.0)(direction => (1 - flight.facing.value.dot(direction.value)) / 2)
    val rangeCost = clamp((distanceToGoal(athlete, flight) - 7) / 39, 0, 1)
    val speedCost = clamp(flight.velocity.value.length / 28, 0, 1)
    val finishing = athlete.ratings.throwing.finishing.value / 100
    val balance = athlete.ratings.physical.balance.value / 100
    val instability = 1 - state.stability.value / 100
    val baseSpread = .65 + 3.0 * (1 - finishing)
    val difficulty = 1 + .70 * rangeCost + .85 * facingCost + .55 * speedCost +
      .20 * (1 - balance) * speedCost + .35 * instability
    math.toRadians(math.min(3.6, baseSpread * difficulty))

  def direction(athlete: Athlete, state: AthleteState, aim: ShotAim, seed: Long): Direction =
    val random = new Random(seed)
    val sigma = standardDeviation(athlete, state, aim)
    val (horizontal, vertical) = Iterator.fill(16)((random.nextGaussian() * sigma, random.nextGaussian() * sigma))
      .find { case (x, y) => math.hypot(x, y) <= maxDeviation }
      .getOrElse((0.0, 0.0))
    val angle = math.hypot(horizontal, vertical)
    if angle < 1e-9 then aim.direction
    else
      val intended = aim.direction.value
      val sideways = Direction.from(intended.cross(Vec3.up)).getOrElse(Direction.east).value
      val up = sideways.cross(intended)
      val tangent = (sideways * horizontal + up * vertical) * (math.sin(angle) / angle)
      Direction.from(intended * math.cos(angle) + tangent).getOrElse(aim.direction)
