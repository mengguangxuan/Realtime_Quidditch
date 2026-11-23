package services.matches.logic

import services.matches.objects.*

import FlightRules.{clamp, contact, move}
import QuaffleRules.{advanceBall, catchQuaffle, free, goal, shoot}

private[matches] object MatchSimulation:
  val ticksPerSecond = 20
  val stepSeconds = 1.0 / ticksPerSecond
  private val kickoff = Vec3(0, 0, 7)
  private val emptySpecial = SpecialBalls(Map.empty, None)

  final case class ContactAttempt(kind: ContactKind, expiresAt: Long)

  final case class Inputs(
      flight: Map[AthleteId, FlightInput] = Map.empty,
      touching: Boolean = false,
      contacts: Map[AthleteId, ContactAttempt] = Map.empty,
      shots: Long = 0
  )

  final case class Step(state: MatchState, events: List[MatchEvent], inputs: Inputs)

  def initialAthlete(side: Side): AthleteState =
    val (x, facing) = if side == Side.Home then (-23.0, Direction.east) else (23.0, Direction.west)
    AthleteState(FlightState(Position(Vec3(x, 0, 7)), Velocity(Vec3.zero), facing),
      Stamina(100), Stability(100), MovementMode.Normal)

  def begin(state: MatchState): MatchState =
    state.copy(phase = MatchPhase.InPlay(MatchBalls(free(kickoff), emptySpecial)))

  def resume(state: MatchState, restart: Restart, special: SpecialBalls): MatchState =
    def possession(side: Side, at: Vec3, defendingAt: Option[Vec3]): MatchState =
      val receiver = state.setup.roster.find(_.side == side).get
      val athletes = state.setup.roster.map { athlete =>
        val entry = state.athletes(athlete.id)
        val point = if athlete.side == side then Some(at) else defendingAt
        athlete.id -> entry.copy(
          flight = entry.flight.copy(position = point.map(Position.apply).getOrElse(entry.flight.position),
            velocity = Velocity(Vec3.zero)),
          movement = MovementMode.Normal
        )
      }.toMap
      state.copy(athletes = athletes, phase = MatchPhase.InPlay(MatchBalls(QuaffleState.HeldBy(receiver.id), special)))
    restart match
      case Restart.Kickoff(side) =>
        val athletes = state.setup.roster.map(athlete => athlete.id -> initialAthlete(athlete.side)).toMap
        val receiver = state.setup.roster.find(_.side == side).get
        state.copy(athletes = athletes, phase = MatchPhase.InPlay(MatchBalls(QuaffleState.HeldBy(receiver.id), special)))
      case Restart.FreePossession(side, at, _) => possession(side, at.value, None)
      case Restart.PenaltyThrow(side, _) =>
        val sign = if side == Side.Home then 1 else -1
        possession(side, Vec3(sign * 33, 0, 12), Some(Vec3(sign * 42, 0, 12)))

  def advance(
      start: MatchState,
      initialBalls: MatchBalls,
      queued: Vector[(AthleteId, Control)],
      previous: Inputs,
      shotSeed: Long
  ): Step =
    var state = start
    var balls = initialBalls
    val events = List.newBuilder[MatchEvent]
    val step = start.clock.elapsed.value + 1
    var contacts = previous.contacts.filter(_._2.expiresAt >= step)
    var flightInputs = previous.flight
    var shots = previous.shots
    var thrower = Option.empty[AthleteId]
    val roster = state.setup.roster

    queued.foreach { case (id, control) =>
      val athlete = roster.find(_.id == id).get
      def reject(reason: ActionRejection): Unit =
        events += MatchEvent.ControlRejected(id, reason)
      def possession(next: QuaffleState, outcome: QuafflePossessionOutcome): Unit =
        balls = balls.copy(quaffle = next)
        events += MatchEvent.QuafflePossessionChanged(outcome)
      control match
        case Control.Fly(input) => flightInputs = flightInputs.updated(id, input)
        case Control.Brace(active) =>
          val entry = state.athletes(id)
          val mode = if active then MovementMode.Bracing else MovementMode.Normal
          state = state.copy(athletes = state.athletes.updated(id, entry.copy(movement = mode)))
        case Control.Catch(CatchTarget.Quaffle) =>
          if catchQuaffle(state, athlete, balls.quaffle) then
            possession(QuaffleState.HeldBy(id), QuafflePossessionOutcome.Caught(id))
        case Control.Shoot(aim) =>
          if balls.quaffle == QuaffleState.HeldBy(id) then
            val motion = shoot(state, athlete, aim, shotSeed ^ (shots * 0x9e3779b97f4a7c15L))
            shots += 1
            thrower = Some(id)
            possession(QuaffleState.Free(motion), QuafflePossessionOutcome.Released(id, motion))
          else reject(ActionRejection.NotBallHolder)
        case Control.Charge | Control.Grab =>
          if !contacts.contains(id) then
            val kind = if control == Control.Grab then ContactKind.Grab else ContactKind.Charge
            contacts = contacts.updated(id, ContactAttempt(kind, step + 4))
            events += MatchEvent.ContactAttempted(id, kind)
        case Control.Catch(CatchTarget.Snitch) | Control.StrikeBludger(_, _) =>
          reject(ActionRejection.BallUnavailable)
    }

    val before = state
    state = move(state, flightInputs, balls.quaffle)
    val first = roster.head
    val second = roster.last
    def offset(value: MatchState): Vec3 =
      value.athletes(second.id).flight.position.value - value.athletes(first.id).flight.position.value
    val relative = offset(state)
    val last = offset(before)
    val change = relative - last
    val fraction = clamp(-last.dot(change) / math.max(change.dot(change), 1e-9), 0, 1)
    val actualContact = (last + change * fraction).length <= 1.45
    if actualContact && !previous.touching then
      val velocity = state.athletes(first.id).flight.velocity.value - state.athletes(second.id).flight.velocity.value
      val initiative = contacts.get(first.id).map(attempt => (first, second, attempt.kind))
        .orElse(contacts.get(second.id).map(attempt => (second, first, attempt.kind)))
        .getOrElse {
          val toward = Direction.from(relative).getOrElse(Direction.east).value
          if velocity.dot(toward) >= 0 &&
              state.athletes(first.id).flight.velocity.value.dot(toward) >=
                -state.athletes(second.id).flight.velocity.value.dot(toward) then
            (first, second, ContactKind.Incidental)
          else (second, first, ContactKind.Incidental)
        }
      val (initiator, opponent, kind) = initiative
      events += MatchEvent.ContactResolved(initiator.id, opponent.id, kind)
      val (updated, updatedBalls, contactEvents) = contact(state, balls, initiator, opponent, kind)
      state = updated
      balls = updatedBalls
      events ++= contactEvents
      contacts = Map.empty
    val touching = relative.length <= 1.65

    state.phase match
      case MatchPhase.InPlay(_) =>
        state = state.copy(phase = MatchPhase.InPlay(balls))
        balls.quaffle match
          case QuaffleState.Free(motion) =>
            advanceBall(motion, before, state, thrower) match
              case QuaffleRules.BallStep.Scored(side) =>
                state = goal(state, balls, side)
                events += MatchEvent.QuaffleGoal(side)
              case QuaffleRules.BallStep.Blocked(by, next) =>
                state = state.copy(phase = MatchPhase.InPlay(balls.copy(quaffle = QuaffleState.Free(next))))
                events += MatchEvent.QuaffleBlocked(by)
              case QuaffleRules.BallStep.Moved(next) =>
                state = state.copy(phase = MatchPhase.InPlay(balls.copy(quaffle = QuaffleState.Free(next))))
          case QuaffleState.HeldBy(_) => ()
      case _ => ()
    val elapsed = PlayTick(step)
    if elapsed.value == state.clock.durationTicks then
      state = state.copy(phase = MatchPhase.Finished(FinishReason.TimeExpired))
      events += MatchEvent.MatchEnded(FinishReason.TimeExpired)
    Step(state.copy(clock = state.clock.copy(elapsed = elapsed)), events.result(),
      Inputs(flightInputs, touching, contacts, shots))
