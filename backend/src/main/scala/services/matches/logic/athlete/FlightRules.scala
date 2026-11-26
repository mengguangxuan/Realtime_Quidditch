package services.matches.logic

import services.matches.objects.*

private[matches] object FlightRules:
  private val dt = MatchSimulation.stepSeconds
  private val pitch = Pitch.alpine

  def move(state: MatchState, inputs: Map[AthleteId, FlightInput], ball: QuaffleState): MatchState =
    val athletes = state.setup.roster.map { athlete =>
      val carrying = ball == QuaffleState.HeldBy(athlete.id)
      athlete.id -> moveAthlete(athlete, state.athletes(athlete.id), inputs.get(athlete.id), carrying)
    }.toMap
    state.copy(athletes = athletes)

  def moveAthlete(athlete: Athlete, previous: AthleteState, input: Option[FlightInput],
      carrying: Boolean): AthleteState =
    val flight = previous.flight
    val ratings = athlete.ratings
    val bracing = previous.movement == MovementMode.Bracing
    val sprinting = !bracing && previous.stamina.value > 0 && input.exists(_.sprint)
    val limit = (7.2 + ratings.flight.speed.value * .096) *
      (if bracing then .6 else if sprinting then 1.25 else 1.0) *
      (if carrying then .72 + ratings.ballControl.carryFlight.value * .002 else 1.0)
    val request = input.fold(Vec3.zero)(i => Vec3(i.forward.value, i.strafe.value, i.climb.value))
    val from = flight.facing.value
    val turnLimit = (.08 + ratings.flight.turning.value * .002) * (if bracing then .55 else 1.0)
    val turnInput = input.flatMap(_.turn)
    val angle = turnInput.fold {
      val target = Vec3(request.x, request.y, 0)
      if target == Vec3.zero then 0.0
      else clamp(math.atan2(from.cross(target).z, from.dot(target)), -turnLimit, turnLimit)
    }(axis => -axis.value * turnLimit)
    val heading = Direction.from(Vec3(from.x * math.cos(angle) - from.y * math.sin(angle),
      from.x * math.sin(angle) + from.y * math.cos(angle), 0)).getOrElse(flight.facing)
    val movement = turnInput.fold(request) { _ =>
      val forward = heading.value
      val right = Vec3(forward.y, -forward.x, 0)
      forward * request.x + right * request.y + Vec3(0, 0, request.z)
    }
    val requested = movement * (1 / math.max(1.0, movement.length))
    val desired = Vec3(requested.x * limit, requested.y * limit,
      requested.z * (3.2 + ratings.flight.climb.value * .064))
    val currentVelocity = flight.velocity.value
    val delta = desired - currentVelocity
    val maxChange = (4.55 + ratings.flight.acceleration.value * .156) * dt
    val velocity = currentVelocity + delta * math.min(1.0, maxChange / math.max(delta.length, 1e-9))
    val position = flight.position.value + velocity * dt
    val bounded = contain(position, 1.5)
    val limitedVelocity = Vec3(
      if bounded.x == position.x then velocity.x else 0,
      if bounded.y == position.y then velocity.y else 0,
      if bounded.z == position.z then velocity.z else 0
    )
    val staminaChange = if sprinting then -(13 - ratings.physical.endurance.value * .09) else 9.0
    val moved = previous.copy(
      flight = FlightState(Position(bounded), Velocity(limitedVelocity), heading),
      stamina = Stamina(clamp(previous.stamina.value + staminaChange * dt, 0, 100)),
      stability = Stability(clamp(previous.stability.value + (5 + ratings.physical.balance.value * .14) * dt, 0, 100)),
      movement = if bracing then MovementMode.Bracing else if sprinting then MovementMode.Sprinting else MovementMode.Normal
    )
    AthleteMotion.flight(moved, input)

  def clamp(value: Double, min: Double, max: Double): Double = math.max(min, math.min(value, max))

  def contain(point: Vec3, floor: Double): Vec3 =
    val radius = math.hypot(point.x / pitch.halfLength, point.y / pitch.halfWidth)
    val factor = math.max(1.0, radius)
    Vec3(point.x / factor, point.y / factor, clamp(point.z, floor, pitch.ceiling))

  final case class ContactImpact(source: AthleteState, target: AthleteState, axis: Vec3, impulse: Double)

  def contact(first: Athlete, source: AthleteState, second: Athlete, target: AthleteState,
      kind: ContactKind): ContactImpact =
    val offset = target.flight.position.value - source.flight.position.value
    val axis = Direction.from(offset).getOrElse(source.flight.facing).value
    val closing = math.max(0, (source.flight.velocity.value - target.flight.velocity.value).dot(axis))
    val effort = kind match
      case ContactKind.Charge => closing + 2
      case ContactKind.Grab => closing * .4 + 3
      case ContactKind.Incidental => closing
    val impulse = effort * (.8 + first.ratings.physical.strength.value * .006)
    val balance = second.ratings.physical.balance.value
    val stability = Stability(clamp(target.stability.value - impulse * (7 - balance * .031), 0, 100))
    val changedTarget = target.copy(stability = stability,
      flight = target.flight.copy(velocity = Velocity(target.flight.velocity.value + axis * (impulse * .32))))
    val changedSource = source.copy(flight = source.flight.copy(
      velocity = Velocity(source.flight.velocity.value - axis * (impulse * .22))))
    ContactImpact(changedSource, changedTarget, axis, impulse)
