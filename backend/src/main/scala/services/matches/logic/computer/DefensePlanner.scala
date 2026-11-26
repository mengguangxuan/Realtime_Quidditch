package services.matches.logic

import services.matches.objects.*
import FlightRules.{clamp, contain}

private[matches] object DefensePlanner:
  private val pitch = Pitch.alpine
  private val horizon = 40
  private val bodyCenter = Vec3(0, 0, .18)

  final case class PredictedBall(at: PlayTick, motion: BallMotion)

  enum InterceptMethod:
    case Catch, Deflect

  enum Decision:
    case HoldGoal(position: Position)
    case CoverShotLane(carrier: AthleteId, gate: GoalRing, position: Position)
    case Press(carrier: AthleteId, cutoff: Position)
    case Challenge(carrier: AthleteId, approach: Position)
    case Intercept(ball: PredictedBall, method: InterceptMethod)
    case PursueLoose(position: Position)

  def flightTo(flight: FlightState, target: Vec3, sprint: Boolean = false): FlightInput =
    val adjustment = (target - flight.position.value) * .24 - flight.velocity.value * .075
    def axis(value: Double): AxisInput = AxisInput(clamp(value, -1, 1))
    FlightInput(axis(adjustment.x), axis(adjustment.y), axis(adjustment.z), sprint)

  private def ownGoal(athlete: Athlete): Double =
    if athlete.side == Side.Home then -pitch.goalPlaneX else pitch.goalPlaneX

  def againstCarrier(state: MatchState, athlete: Athlete, carrier: AthleteId,
      approach: DefensiveApproach = DefensiveApproach.Pressure): Decision =
    val defender = state.athletes(athlete.id).flight
    val opponent = state.athletes(carrier).flight
    val point = opponent.position.value
    val goalX = ownGoal(athlete)
    val goalward = math.signum(goalX)
    val advanced = (point.x - goalX) * -goalward
    val toCarrier = point - defender.position.value
    val distance = toCarrier.length
    val direction = Direction.from(toCarrier).getOrElse(defender.facing).value
    val closing = (defender.velocity.value - opponent.velocity.value).dot(direction)
    val impulse = (closing + 2) * (.8 + athlete.ratings.physical.strength.value * .006)
    val legalContact = direction.dot(opponent.facing.value) < .55 && closing > 2 &&
      impulse < 23 && distance <= math.min(3.6, 1.30 + closing * .20)
    val prediction = contain(point + opponent.velocity.value * .45, 1.5)
    val pressure = approach == DefensiveApproach.Pressure
    if pressure && legalContact then Decision.Challenge(carrier, Position(prediction))
    else if pressure && advanced >= 15 && distance <= 11 && toCarrier.x * goalward <= 2.5 then
      Decision.Press(carrier, Position(contain(prediction + Vec3(goalward * 1.8, 0, 0), 1.5)))
    else if advanced > 27 || advanced <= 0 then
      Decision.HoldGoal(Position(Vec3(goalX - goalward * 4, 0, 10)))
    else
      val gate = pitch.rings.maxBy { ring =>
        val target = Vec3(goalX, ring.y, ring.height) - prediction
        Direction.from(target).getOrElse(opponent.facing).value.dot(opponent.facing.value) - target.length * .012
      }
      val center = Vec3(goalX, gate.y, gate.height)
      val span = math.abs(center.x - prediction.x)
      val fraction = clamp(1 - 4 / math.max(.1, span), .35, .88)
      val lane = prediction + (center - prediction) * fraction - bodyCenter
      Decision.CoverShotLane(carrier, gate, Position(contain(lane, 1.5)))

  def againstFreeBall(state: MatchState, athlete: Athlete, initial: BallMotion,
      canUseCatch: Boolean): Decision =
    val physics = state.setup.balls.quaffle
    val role = state.setup.roles(athlete.id)
    val radius = QuaffleRules.contactRadius(athlete, role, physics.radiusMeters)
    val defender = state.athletes(athlete.id)
    val clock = state.clock.elapsed.value
    if canUseCatch && QuaffleRules.canCatch(athlete, defender.flight, initial, physics) then
      Decision.Intercept(PredictedBall(PlayTick(clock), initial), InterceptMethod.Catch)
    else
      var forecast = initial
      var goal = Option.empty[(Side, Vec3)]
      var elapsed = 0
      var best = Option.empty[Decision]
      while elapsed < horizon && goal.isEmpty && best.isEmpty do
        val (next, crossing) = QuaffleRules.predictStep(forecast, physics)
        elapsed += 1
        val stoppingAtGoal = crossing.nonEmpty
        if !stoppingAtGoal then
          val ball = PredictedBall(PlayTick(clock + elapsed), next)
          val target = interceptPosition(ball)
          val arrived = projectFlight(athlete, defender, target, elapsed)
          if canUseCatch && QuaffleRules.canCatch(athlete, arrived.flight, next, physics) then
            best = Some(Decision.Intercept(ball, InterceptMethod.Catch))
          else if (arrived.flight.position.value + bodyCenter - next.position.value).length <= radius * .90 then
            best = Some(Decision.Intercept(ball, InterceptMethod.Deflect))
        forecast = next
        goal = crossing
      best.getOrElse {
        val goalX = ownGoal(athlete)
        val goalward = math.signum(goalX)
        val threat = goal.filter(_._1 != athlete.side)
        threat match
          case Some((_, point)) =>
            Decision.HoldGoal(Position(contain(Vec3(goalX - goalward * 1.2, point.y, point.z - bodyCenter.z), 1.5)))
          case None =>
            Decision.PursueLoose(Position(interceptPosition(PredictedBall(PlayTick(clock + elapsed), forecast))))
      }

  def interceptPosition(ball: PredictedBall): Vec3 = contain(ball.motion.position.value - bodyCenter, 1.5)

  private def projectFlight(athlete: Athlete, start: AthleteState, target: Vec3, ticks: Int): AthleteState =
    (1 to ticks).foldLeft(start) { (state, _) =>
      FlightRules.moveAthlete(athlete, state, Some(flightTo(state.flight, target)), carrying = false)
    }
