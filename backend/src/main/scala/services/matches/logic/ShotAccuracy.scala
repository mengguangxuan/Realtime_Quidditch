package services.matches.logic

import scala.util.Random
import services.matches.objects.*
import FlightRules.clamp

private[matches] object ShotAccuracy:
  private val pitch = Pitch.alpine

  def distanceToGoal(athlete: Athlete, flight: FlightState): Double =
    val goalX = if athlete.side == Side.Home then pitch.goalPlaneX else -pitch.goalPlaneX
    pitch.rings.map(ring => (Vec3(goalX, ring.y, ring.height) - flight.position.value).length).min

  def probability(athlete: Athlete, state: AthleteState, aim: ShotAim): Double =
    val flight = state.flight
    val attempt = aim.direction.value
    val horizontal = Direction.from(Vec3(attempt.x, attempt.y, 0)).toOption
    val facingCost = horizontal.fold(1.0)(direction => (1 - flight.facing.value.dot(direction.value)) / 2)
    val rangeCost = clamp((distanceToGoal(athlete, flight) - 7) / 39, 0, 1)
    val speedCost = clamp(flight.velocity.value.length / 28, 0, 1)
    val finishing = athlete.ratings.throwing.finishing.value / 100
    val balance = athlete.ratings.physical.balance.value / 100
    val instability = 1 - state.stability.value / 100
    clamp(.97 - .47 * rangeCost - .50 * facingCost - .24 * speedCost -
      .22 * (1 - finishing) - .08 * (1 - balance) * speedCost - .19 * instability, .04, .97)

  def direction(athlete: Athlete, state: AthleteState, aim: ShotAim, seed: Long): Direction =
    val random = new Random(seed)
    if random.nextDouble() < probability(athlete, state, aim) then aim.direction
    else
      val intended = aim.direction.value
      val sideways = Direction.from(intended.cross(Vec3.up)).getOrElse(Direction.east).value
      val vertical = sideways.cross(intended)
      val horizontalError = (random.nextDouble() - .5) * .26
      val verticalError = (if random.nextBoolean() then 1 else -1) *
        (.13 + math.atan2(2.1, math.max(2, distanceToGoal(athlete, state.flight))))
      Direction.from(intended + sideways * horizontalError + vertical * verticalError).getOrElse(aim.direction)
