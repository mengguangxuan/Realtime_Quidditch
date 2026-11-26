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
    val pressure = spaceAt(state, opponents, position, 0)

    val depth = (goalX - position.x) * direction
    val shot = Option.when(allowed.contains(ControlKind.Shoot) && depth > 0 && depth <= 23) {
      // The trial supplies release speed and reach. Forecasts aim centrally without sampling the match's shot seed.
      val trial = QuaffleRules.shoot(state, athlete, ShotAim(Direction.east, power, None), 0)
      val speed = (trial.velocity.value - velocity).length
      val reach = (trial.position.value - position).length
      pitch.rings.flatMap { ring =>
        val target = Vec3(goalX, ring.y, ring.height) - position
        if target.length > 23 then None
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
                Option.when(goal.clearance >= .35)((shotAim, math.min(goal.clearance, 6) - spread))
              }
            }
          }
      }.maxByOption(_._2).map(candidate => Decision.Shoot(candidate._1))
    }.flatten

    shot.orElse {
      Option.when(allowed.contains(ControlKind.Pass)) {
        others.filter(_.side == athlete.side).flatMap { teammate =>
          val receiver = state.athletes(teammate.id).flight
          val offset = receiver.position.value - position
          val progress = offset.x * direction
          val space = spaceAt(state, opponents, receiver.position.value, .25)
          val forward = progress >= 9 && space >= math.max(5, pressure - 2)
          val outlet = pressure < 7 && space >= pressure + 2 && progress >= -5
          val useful = forward || outlet
          if offset.length < 4 || offset.length > 24 || !useful || space < 3 then None
          else
            val release = QuaffleRules.pass(state, athlete, teammate.id)
            path(state, release, opponents, 30).takeWhile(_.goal.isEmpty).find { sample =>
              val predicted = receiver.copy(position = Position(project(receiver, sample.tick * dt)))
              QuaffleRules.canCatch(teammate, predicted, sample.motion, physics)
            }.flatMap { caught =>
              Option.when(caught.clearance >= .85)((teammate.id, space + progress * .45))
            }
        }.maxByOption { case (id, value) => (value, -id.value) }.map(candidate => Decision.Pass(candidate._1))
      }.flatten
    }.getOrElse {
      val advance = math.min(pitch.goalPlaneX - 5, position.x * direction + 11) * direction
      val lanes = List(-10.0, 0.0, 10.0).map(y =>
        FlightRules.contain(Vec3(advance, y, FlightRules.clamp(position.z, 8, 13)), 1.5))
      Decision.Carry(lanes.maxBy(point => spaceAt(state, opponents, point, .35) - (point - position).length * .35))
    }

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
