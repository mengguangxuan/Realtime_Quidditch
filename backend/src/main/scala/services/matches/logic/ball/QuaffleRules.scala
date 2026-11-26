package services.matches.logic

import services.matches.objects.*

import FlightRules.contain

private[matches] object QuaffleRules:
  private val dt = MatchSimulation.stepSeconds
  private val pitch = Pitch.alpine

  enum BallStep:
    case Moved(motion: BallMotion, hitBoundary: Boolean)
    case Blocked(by: AthleteId, motion: BallMotion)
    case Scored(side: Side)

  def free(position: Vec3, velocity: Vec3 = Vec3.zero): QuaffleState.Free =
    QuaffleState.Free(BallMotion(Position(position), Velocity(velocity), AngularVelocity(Vec3.zero)))

  def catchQuaffle(state: MatchState, athlete: Athlete, ball: QuaffleState): Boolean =
    ball match
      case QuaffleState.Free(motion) =>
        canCatch(athlete, state.athletes(athlete.id).flight, motion, state.setup.balls.quaffle)
      case QuaffleState.HeldBy(_) => false

  def canCatch(athlete: Athlete, flight: FlightState, motion: BallMotion, physics: BallPhysics): Boolean =
    (flight.position.value - motion.position.value).length <= 1.6 + physics.radiusMeters &&
      (flight.velocity.value - motion.velocity.value).length <= 5 + athlete.ratings.ballControl.catching.value * .15

  def shoot(state: MatchState, athlete: Athlete, aim: ShotAim, seed: Long): BallMotion =
    val athleteState = state.athletes(athlete.id)
    val flight = athleteState.flight
    val ratings = athlete.ratings.throwing
    val speed = 5 + aim.power.value * (17 + ratings.power.value * .15)
    val trajectory = ShotAccuracy.direction(athlete, athleteState, aim, seed).value
    BallMotion(Position(flight.position.value + trajectory * .65), Velocity(flight.velocity.value + trajectory * speed),
      AngularVelocity(Vec3.up * (aim.spin.fold(0.0)(_.value) * (5 + ratings.curve.value * .2))))

  def pass(state: MatchState, athlete: Athlete, receiver: AthleteId): BallMotion =
    val from = state.athletes(athlete.id).flight
    val to = state.athletes(receiver).flight
    val offset = to.position.value - from.position.value
    val speed = 11 + athlete.ratings.throwing.shortPass.value * .07
    val time = FlightRules.clamp(offset.length / speed, .25, 1.8)
    val target = to.position.value + to.velocity.value * time
    val aim = target - from.position.value + Vec3.up * (4.905 * time * time)
    val direction = Direction.from(aim).getOrElse(from.facing).value
    val release = from.position.value + direction * math.min(.65, offset.length * .4)
    val velocity = (target - release) * (1 / time) + Vec3.up * (4.905 * time)
    val relative = velocity - from.velocity.value
    val limit = 18 + athlete.ratings.throwing.longPass.value * .12
    free(release, from.velocity.value + relative * math.min(1, limit / math.max(relative.length, 1e-9))).motion

  def advanceBall(motion: BallMotion, before: MatchState, after: MatchState,
      thrower: Option[AthleteId]): BallStep =
    val physics = after.setup.balls.quaffle
    val point = motion.position.value
    val nextVelocity = velocityAfterStep(motion, physics)
    val next = point + nextVelocity * dt
    val crossing = goalCrossing(point, next, physics)
    val block = firstHit(point, next, before, after, thrower)
      .filter(hit => crossing.forall { case (_, time) => hit.time <= time })
    block match
      case Some(hit) => BallStep.Blocked(hit.athlete, bounceOffAthlete(motion, nextVelocity, hit))
      case None => crossing match
        case Some((side, _)) => BallStep.Scored(side)
        case None =>
          val bounded = moveWithinPitch(motion, next, nextVelocity, physics)
          BallStep.Moved(bounded, bounded.position.value != next)

  def predictStep(motion: BallMotion, physics: BallPhysics): (BallMotion, Option[(Side, Vec3)]) =
    val point = motion.position.value
    val velocity = velocityAfterStep(motion, physics)
    val next = point + velocity * dt
    val goal = goalCrossing(point, next, physics)
      .map { case (side, time) => side -> (point + (next - point) * time) }
    (moveWithinPitch(motion, next, velocity, physics), goal)

  private def velocityAfterStep(motion: BallMotion, physics: BallPhysics): Vec3 =
    val velocity = motion.velocity.value
    val drag = 1 / (1 + physics.dragCoefficient * velocity.length * dt / physics.massKg)
    val curve = motion.spin.radiansPerSecond.cross(velocity) * (physics.spinLiftCoefficient / physics.massKg)
    velocity * drag + (curve + Vec3(0, 0, -9.81)) * dt

  private def goalCrossing(point: Vec3, next: Vec3, physics: BallPhysics): Option[(Side, Double)] =
    def crosses(plane: Double): Option[Double] =
      val t = (plane - point.x) / (next.x - point.x)
      val y = point.y + (next.y - point.y) * t
      val z = point.z + (next.z - point.z) * t
      Option.when(pitch.rings.exists { ring =>
        math.hypot(y - ring.y, z - ring.height) <= ring.innerRadius - physics.radiusMeters
      })(t)
    if point.x < pitch.goalPlaneX && next.x >= pitch.goalPlaneX then crosses(pitch.goalPlaneX).map(Side.Home -> _)
    else if point.x > -pitch.goalPlaneX && next.x <= -pitch.goalPlaneX then crosses(-pitch.goalPlaneX).map(Side.Away -> _)
    else None

  private def moveWithinPitch(motion: BallMotion, next: Vec3, nextVelocity: Vec3, physics: BallPhysics): BallMotion =
      val bounded = contain(next, physics.radiusMeters)
      val bounce = Vec3(
        if bounded.x == next.x then nextVelocity.x else nextVelocity.x * -.45,
        if bounded.y == next.y then nextVelocity.y else nextVelocity.y * -.45,
        if bounded.z == next.z then nextVelocity.z else 0
      )
      val friction = if bounded.z <= physics.radiusMeters then .91 else 1.0
      BallMotion(Position(bounded), Velocity(bounce * friction), motion.spin)

  def contactRadius(athlete: Athlete, role: AthleteRole, ballRadius: Double): Double =
    ballRadius + (if role == AthleteRole.Keeper then .85 + athlete.ratings.goalkeeping.reach.value * .003 else .75)

  private final case class Hit(athlete: AthleteId, time: Double, center: Vec3, normal: Vec3, radius: Double, velocity: Vec3)

  private def firstHit(start: Vec3, end: Vec3, before: MatchState, after: MatchState, ignore: Option[AthleteId]): Option[Hit] =
    val ballRadius = after.setup.balls.quaffle.radiusMeters
    val hits = after.setup.roster.flatMap { athlete =>
      val id = athlete.id
      if ignore.contains(id) then None
      else
        val oldCenter = before.athletes(id).flight.position.value + Vec3(0, 0, .18)
        val newCenter = after.athletes(id).flight.position.value + Vec3(0, 0, .18)
        val movement = newCenter - oldCenter
        val radius = contactRadius(athlete, after.setup.roles(id), ballRadius)
        val relative = start - oldCenter
        val delta = end - start - movement
        val a = delta.dot(delta)
        val b = relative.dot(delta)
        val c = relative.dot(relative) - radius * radius
        val discriminant = b * b - a * c
        if a < 1e-9 || b >= 0 || discriminant < 0 then None
        else
          val time = if c <= 0 then 0.0 else (-b - math.sqrt(discriminant)) / a
          Option.when(time >= 0 && time <= 1) {
            val normal = Direction.from(relative + delta * time)
              .orElse(Direction.from(delta * -1)).getOrElse(Direction.east).value
            Hit(id, time, oldCenter + movement * time, normal, radius,
              movement * (1 / dt))
          }
    }
    hits.minByOption(hit => (hit.time, hit.athlete.value))

  private def bounceOffAthlete(motion: BallMotion, incoming: Vec3, hit: Hit): BallMotion =
    val relativeVelocity = incoming - hit.velocity
    val normalVelocity = hit.normal * relativeVelocity.dot(hit.normal)
    val tangentVelocity = relativeVelocity - normalVelocity
    val rebounded = hit.velocity + tangentVelocity * .84 - normalVelocity * .55
    motion.copy(position = Position(hit.center + hit.normal * (hit.radius + .005)),
      velocity = Velocity(rebounded))
