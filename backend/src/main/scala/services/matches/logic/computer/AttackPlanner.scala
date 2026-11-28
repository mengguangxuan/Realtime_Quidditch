package services.matches.logic

import services.matches.objects.*

private[logic] object AttackPlanner:
  enum Decision:
    case Shoot(aim: ShotAim)
    case Pass(receiver: AthleteId)
    case Carry(target: Vec3)

  private val pitch = Pitch.alpine
  private val power = ShotPower(1)
  private val dt = MatchSimulation.stepSeconds
  private val shootingRange = 23.0

  def withQuaffle(state: MatchState, athlete: Athlete, allowed: Set[ControlKind]): Decision =
    val shooter = state.athletes(athlete.id)
    val flight = shooter.flight
    val position = flight.position.value
    val velocity = flight.velocity.value
    val direction = if athlete.side == Side.Home then 1 else -1
    val goalX = pitch.goalPlaneX * direction
    val opponents = state.setup.roster.filter(_.side != athlete.side)
    val others = state.setup.roster.filterNot(_.id == athlete.id)
    val physics = state.setup.balls.quaffle
    val depth = (goalX - position.x) * direction
    def possessionValue(point: Vec3, seconds: Double): Double =
      val progress = (point.x - position.x) * direction
      val nextDepth = (goalX - point.x) * direction
      val space = math.min(12, spaceAt(state, opponents, point, seconds))
      val opensShot = if depth > shootingRange && nextDepth > 0 && nextDepth <= shootingRange then 3.0 else 0.0
      progress * .65 + space * .5 + opensShot - math.abs(point.y) * .32 - seconds * 2

    val shots: List[(Decision, Double)] =
      if !allowed.contains(ControlKind.Shoot) || depth <= 0 || depth > shootingRange then Nil
      else
        // The trial supplies release speed and reach. Forecasts aim centrally without sampling the match's shot seed.
        val trial = QuaffleRules.shoot(state, athlete, ShotAim(Direction.east, power, None), 0)
        val speed = (trial.velocity.value - velocity).length
        val reach = (trial.position.value - position).length
        pitch.rings.flatMap { ring =>
          val target = Vec3(goalX, ring.y, ring.height) - position
          if target.length > shootingRange then None
          else
            Direction.from(target).toOption.flatMap { along =>
              val travel = (target.length - reach) / math.max(16, speed + velocity.dot(along.value))
              val aim = target - velocity * travel + Vec3.up * (4.905 * travel * travel)
              Direction.from(aim).toOption.flatMap { direction =>
                val shotAim = ShotAim(direction, power, None)
                val motion = trial.copy(position = Position(position + direction.value * reach),
                  velocity = Velocity(velocity + direction.value * speed))
                path(state, motion, others, 24).find(_.goal.contains(athlete.side)).flatMap { goal =>
                  val spread = ShotAccuracy.standardDeviation(athlete, shooter, shotAim) * target.length
                  val margin = math.min(goal.clearance, ring.innerRadius - physics.radiusMeters) - spread
                  Option.when(goal.clearance >= .35)((Decision.Shoot(shotAim), 16 + margin * 3 - goal.tick * dt * 2))
                }
              }
            }
        }

    val passes: List[(Decision, Double)] =
      if !allowed.contains(ControlKind.Pass) then Nil
      else others.filter(_.side == athlete.side).sortBy(_.id.value).flatMap { teammate =>
        val receiver = state.athletes(teammate.id).flight
        val distance = (receiver.position.value - position).length
        if distance < 4 || distance > 24 then None
        else
          val release = QuaffleRules.pass(state, athlete, teammate.id)
          path(state, release, opponents, 30)
            .takeWhile(sample => sample.goal.isEmpty && sample.clearance >= .85).collectFirst {
              case sample if QuaffleRules.canCatch(teammate,
                  receiver.copy(position = Position(project(receiver, sample.tick * dt))), sample.motion, physics) =>
                val seconds = sample.tick * dt
                val reception = project(receiver, seconds)
                val crowding = math.max(0, 5 - spaceAt(state, opponents, reception, seconds))
                val score = possessionValue(reception, seconds) + math.min(4, sample.clearance) * .5 - crowding * 2 - 3
                Decision.Pass(teammate.id) -> score
          }
      }

    val keeper = state.setup.roles(athlete.id) == AthleteRole.Keeper
    val limit = if keeper then -pitch.goalPlaneX + 12 else pitch.goalPlaneX - 5
    val advance = math.min(limit, position.x * direction + 11) * direction
    val lanes = if allowed.contains(ControlKind.Fly) then List(0.0, position.y, -10.0, 10.0).distinct else Nil
    val carries: List[(Decision, Double)] = lanes.map { y =>
      val target = FlightRules.contain(Vec3(advance, y, FlightRules.clamp(position.z, 8, 13)), 1.5)
      val (arrival, tightest) = (1 to MatchClock.ticksPerSecond).foldLeft((shooter, Double.PositiveInfinity)) {
        case ((step, clearance), tick) =>
          val moved = FlightRules.moveAthlete(athlete, step, Some(DefensePlanner.flightTo(step.flight, target)), carrying = true)
          val gap = spaceAt(state, opponents, moved.flight.position.value, tick * dt)
          (moved, math.min(clearance, gap))
      }
      val score = possessionValue(arrival.flight.position.value, 1) - math.max(0, 5 - tightest) * 3
      Decision.Carry(target) -> score
    }
    (shots ++ passes ++ carries).maxByOption(_._2).fold(Decision.Carry(position))(_._1)

  private final case class Sample(tick: Int, motion: BallMotion, goal: Option[Side], clearance: Double)

  private def path(state: MatchState, start: BallMotion, obstacles: List[Athlete], ticks: Int): Iterator[Sample] =
    val physics = state.setup.balls.quaffle
    val defenders = obstacles.map { athlete =>
      state.athletes(athlete.id).flight ->
        QuaffleRules.contactRadius(athlete, state.setup.roles(athlete.id), physics.radiusMeters)
    }
    Iterator.iterate(Sample(0, start, None, Double.PositiveInfinity)) { previous =>
      val tick = previous.tick + 1
      val (motion, goal) = QuaffleRules.predictStep(previous.motion, physics)
      val begin = previous.motion.position.value
      val segment = goal.fold(motion.position.value)(_._2) - begin
      val clearance = defenders.map { (flight, body) =>
        val offset = project(flight, (tick - .5) * dt, .5) + Vec3(0, 0, .18) - begin
        val fraction = FlightRules.clamp(offset.dot(segment) / math.max(segment.dot(segment), 1e-9), 0, 1)
        (offset - segment * fraction).length - body
      }.minOption.getOrElse(Double.PositiveInfinity)
      Sample(tick, motion, goal.map(_._1), math.min(previous.clearance, clearance))
    }.drop(1).take(ticks)

  private def spaceAt(state: MatchState, opponents: List[Athlete], point: Vec3, seconds: Double): Double =
    opponents.map(athlete => (point - project(state.athletes(athlete.id).flight, seconds)).length)
      .minOption.getOrElse(Double.PositiveInfinity)

  private def project(flight: FlightState, seconds: Double, horizon: Double = 1.5): Vec3 =
    FlightRules.contain(flight.position.value + flight.velocity.value * math.min(seconds, horizon), 1.5)
