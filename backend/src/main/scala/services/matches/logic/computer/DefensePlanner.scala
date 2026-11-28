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

  private def threatenedRing(opponent: FlightState, predicted: Vec3, goalX: Double): GoalRing =
    pitch.rings.maxBy { ring =>
      val offset = Vec3(goalX, ring.y, ring.height) - predicted
      Direction.from(offset).getOrElse(opponent.facing).value.dot(opponent.facing.value) - offset.length * .012
    }

  private def pressurePosition(opponent: FlightState, goalX: Double): (GoalRing, Vec3, Vec3) =
    val ball = contain(opponent.position.value + opponent.velocity.value * .35, 1.5)
    val ring = threatenedRing(opponent, ball, goalX)
    val toGoal = Vec3(goalX, ring.y, ring.height) - ball
    val inward = if goalX > 0 then Direction.east else Direction.west
    val axis = Direction.from(toGoal).getOrElse(inward).value
    val depth = clamp(toGoal.dot(axis) * .5, 0, 3)
    (ring, contain(ball + axis * depth - bodyCenter, 1.5), axis)

  def pressureCosts(state: MatchState, defending: List[Athlete], carrier: AthleteId): List[(Athlete, Double)] =
    val opponent = state.athletes(carrier).flight
    val (_, target, axis) = pressurePosition(opponent, ownGoal(defending.head))
    val projectedCarrier = contain(opponent.position.value + opponent.velocity.value * .35, 1.5)
    defending.map { athlete =>
      val start = state.athletes(athlete.id)
      val projected = projectFlight(athlete, start, target, 7, sprint = true).flight.position.value
      val behind = math.max(0, (projectedCarrier - projected).dot(axis))
      athlete -> ((projected - target).length + behind * 1.5)
    }

  def guardGoal(athlete: Athlete, ball: Vec3): Decision =
    val goalX = ownGoal(athlete)
    val nearGoal = math.abs(ball.x - goalX) <= 24
    val lateral = if nearGoal then clamp(ball.y * .42, -6, 6) else 0.0
    val height = if nearGoal then clamp(ball.z, 7.5, 11.5) else 10.0
    Decision.HoldGoal(Position(Vec3(goalX - math.signum(goalX) * 3, lateral, height)))

  def keeperAgainstFreeBall(state: MatchState, athlete: Athlete, ball: BallMotion, canCatch: Boolean): Decision =
    def inRange(point: Vec3): Boolean =
      math.abs(point.x - ownGoal(athlete)) <= 17 && math.abs(point.y) <= 13
    if inRange(ball.position.value) then againstFreeBall(state, athlete, ball, canCatch) match
      case Decision.PursueLoose(target) if !inRange(target.value) =>
        guardGoal(athlete, ball.position.value)
      case Decision.Intercept(sample, _) if !inRange(interceptPosition(sample)) =>
        guardGoal(athlete, ball.position.value)
      case decision => decision
    else guardGoal(athlete, ball.position.value)

  def againstCarrier(state: MatchState, athlete: Athlete, carrier: AthleteId): Decision =
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
    val pressure = state.setup.tacticsFor(athlete.side).defense == DefensiveApproach.Pressure
    val teamSize = state.setup.roster.count(_.side == athlete.side)
    val hasCover = teamSize > 1
    val (ring, cutoff, axis) = pressurePosition(opponent, goalX)
    val onGoalSide = toCarrier.dot(axis) <= -.25
    val containsCarrier = onGoalSide && distance <= 9 && (defender.position.value - cutoff).length <= 6
    if legalContact && (pressure || (teamSize > 2 && containsCarrier)) then
      Decision.Challenge(carrier, Position(prediction))
    else if teamSize > 2 && advanced > 0 && containsCarrier then
      // Give the flight controller enough lead to track a moving carrier without braking out of the lane.
      val trackingTarget = contain(cutoff + opponent.velocity.value * .30, 1.5)
      Decision.CoverShotLane(carrier, ring, Position(trackingTarget))
    else if teamSize > 2 && advanced > 0 && pressure then
      Decision.Press(carrier, Position(cutoff))
    else if pressure && hasCover && advanced > 0 then
      val flank = if toCarrier.x * goalward <= 2.5 then 0.0
        else if defender.position.value.y < point.y then -2.4 else 2.4
      val cutoff = contain(prediction + Vec3(goalward * 2.4, flank, 0), 1.5)
      Decision.Press(carrier, Position(cutoff))
    else if pressure && advanced >= 15 && distance <= 11 && toCarrier.x * goalward <= 2.5 then
      Decision.Press(carrier, Position(contain(prediction + Vec3(goalward * 1.8, 0, 0), 1.5)))
    else if advanced > 27 || advanced <= 0 then
      Decision.HoldGoal(Position(Vec3(goalX - goalward * 4, 0, 10)))
    else
      val gate = threatenedRing(opponent, prediction, goalX)
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

  private def projectFlight(athlete: Athlete, start: AthleteState, target: Vec3, ticks: Int,
      sprint: Boolean = false): AthleteState =
    (1 to ticks).foldLeft(start) { (state, _) =>
      FlightRules.moveAthlete(athlete, state, Some(flightTo(state.flight, target, sprint)), carrying = false)
    }
