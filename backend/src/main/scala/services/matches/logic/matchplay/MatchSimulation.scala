package services.matches.logic

import services.matches.objects.*

import FlightRules.{clamp, contact, move}
import QuaffleRules.{advanceBall, catchQuaffle, free, pass, shoot}

private[matches] object MatchSimulation:
  val ticksPerSecond = MatchClock.ticksPerSecond
  val stepSeconds = 1.0 / ticksPerSecond
  private val kickoff = Vec3(0, 0, 7)
  private val emptySpecial = SpecialBalls(Map.empty, None)
  val startingHeight = 7.0
  val startingClearance = 3.0
  val startingSpacing = 3.0

  final case class ContactAttempt(kind: ContactKind, expiresAt: Long)

  final case class Inputs(
      flight: Map[AthleteId, FlightInput] = Map.empty,
      touching: Set[(AthleteId, AthleteId)] = Set.empty,
      contacts: Map[AthleteId, ContactAttempt] = Map.empty,
      shots: Long = 0,
      passTarget: Option[(AthleteId, Long)] = None,
      shotUntil: Option[Long] = None,
      primaryDefender: Option[AthleteId] = None,
      catchRetryAt: Map[AthleteId, Long] = Map.empty
  )

  final case class Step(state: MatchState, events: List[MatchEvent], inputs: Inputs)

  def initialAthlete(side: Side): AthleteState =
    val (x, facing) = if side == Side.Home then (-23.0, Direction.east) else (23.0, Direction.west)
    AthleteState(FlightState(Position(Vec3(x, 0, startingHeight)), Velocity(Vec3.zero), facing),
      Stamina(100), Stability(100), MovementMode.Normal)

  def startingSlot(side: Side, index: Int): Position =
    val start = initialAthlete(side).flight.position.value
    val sign = if side == Side.Home then 1 else -1
    Position(start + Vec3(0, sign * index * 8, 0))

  def formation(roster: List[Athlete], positions: Map[AthleteId, Position] = Map.empty): Map[AthleteId, AthleteState] =
    Side.values.toList.flatMap { side =>
      roster.filter(_.side == side).zipWithIndex.map { (athlete, slot) =>
        val entry = initialAthlete(side)
        val position = positions.getOrElse(athlete.id, startingSlot(side, slot))
        athlete.id -> entry.copy(flight = entry.flight.copy(position = position))
      }
    }.toMap

  def startingPositionsValid(roster: List[Athlete], positions: Map[AthleteId, Position]): Either[String, Unit] =
    val home = roster.filter(_.side == Side.Home)
    val pitch = Pitch.alpine
    val points = home.zipWithIndex.map { (athlete, index) =>
      positions.getOrElse(athlete.id, startingSlot(Side.Home, index)).value
    }
    if !positions.keySet.subsetOf(home.map(_.id).toSet) then Left("Invalid starting athlete")
    else if !points.forall(point =>
      point.x >= -pitch.goalPlaneX && point.x <= -startingClearance && point.z == startingHeight &&
        math.hypot(point.x / pitch.halfLength, point.y / pitch.halfWidth) <= 1
    ) then Left("Invalid starting position")
    else if points.combinations(2).exists(pair => (pair.head - pair.last).length < startingSpacing) then
      Left("Starting players too close")
    else Right(())

  def begin(state: MatchState): MatchState =
    state.copy(phase = MatchPhase.InPlay(MatchBalls(free(kickoff), emptySpecial)))

  def resume(state: MatchState, restart: Restart, special: SpecialBalls, rules: MatchRules): MatchState =
    def receiver(side: Side): AthleteId =
      state.setup.players.find(player => player.side == side && player.kind == PlayerKind.Human)
        .flatMap(player => state.controls.find(_.player == player.id).map(_.athlete))
        .getOrElse(state.setup.roster.find(_.side == side).get.id)

    def place(entry: AthleteState, at: Vec3, facing: Direction): AthleteState =
      entry.copy(flight = FlightState(Position(at), Velocity(Vec3.zero), facing),
        movement = MovementMode.Normal, pose = None)

    def possession(side: Side, who: AthleteId, athletes: Map[AthleteId, AthleteState]): MatchState =
      state.copy(athletes = athletes, attackClock = Some(rules.attackFor(side)),
        phase = MatchPhase.InPlay(MatchBalls(QuaffleState.HeldBy(who), special)))

    restart match
      case Restart.Kickoff(side) =>
        possession(side, receiver(side), formation(state.setup.roster))
      case Restart.TeamPossession(side, at, _) =>
        val who = receiver(side)
        val reset = formation(state.setup.roster)
        possession(side, who, reset.updated(who, place(reset(who), at.value, initialAthlete(side).flight.facing)))
      case Restart.FreePossession(side, at, _) =>
        val who = receiver(side)
        val stopped = state.athletes.map { (id, entry) =>
          val position = if id == who then at.value else entry.flight.position.value
          id -> place(entry, position, entry.flight.facing)
        }
        possession(side, who, stopped)
      case Restart.PenaltyThrow(side, foul) =>
        val who = foul.victim
        val defense = MatchRules.opponent(side)
        val defender = receiver(defense)
        val placed = formation(state.setup.roster)
        val athletes = placed
          .updated(who, place(placed(who), rules.penaltySpot(side), initialAthlete(side).flight.facing))
          .updated(defender, place(placed(defender), rules.penaltyDefenderSpot(side), initialAthlete(defense).flight.facing))
        val controls = state.setup.players.find(player => player.side == side && player.kind == PlayerKind.Human)
          .flatMap(player => PlayerControl.switchTo(state.setup, state.controls, player.id, who).toOption)
          .getOrElse(state.controls)
        state.copy(athletes = athletes, controls = controls, attackClock = None,
          phase = MatchPhase.Penalty(MatchBalls(QuaffleState.HeldBy(who), special), side, who, rules.penaltyAimTicks))

  def advancePenalty(start: MatchState, penalty: MatchPhase.Penalty,
      queued: Vector[(AthleteId, Control)], inputs: Inputs, shotSeed: Long, rules: MatchRules): Step =
    val shooter = penalty.shooter
    val state = AthleteMotion.tick(start)

    def resolve(outcome: MatchRules.PenaltyResult, nextInputs: Inputs): Step =
      val (finished, events) = rules.finishPenalty(state, penalty, outcome)
      Step(finished, events, nextInputs)

    def advanceShot(motion: BallMotion, ticksLeft: Long, nextInputs: Inputs): Step =
      val flight = advanceBall(motion, state, state, Some(shooter))
      rules.penaltyShotResult(penalty, flight, ticksLeft) match
        case Left(next) =>
          val balls = penalty.balls.copy(quaffle = QuaffleState.Free(next))
          Step(state.copy(phase = penalty.copy(balls = balls, remainingTicks = ticksLeft)), Nil, nextInputs)
        case Right(outcome) => resolve(outcome, nextInputs)

    penalty.balls.quaffle match
      case QuaffleState.Free(motion) =>
        advanceShot(motion, penalty.remainingTicks - 1, inputs)
      case QuaffleState.HeldBy(holder) =>
        val aim = queued.collectFirst {
          case (id, Control.Shoot(aim)) if id == holder && rules.canTakePenalty(penalty, id) => aim
        }
        aim match
          case Some(shotAim) =>
            val athlete = state.setup.roster.find(_.id == shooter).get
            val motion = shoot(state, athlete, shotAim, seedForShot(shotSeed, inputs.shots))
            val released = MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Released(shooter, motion))
            val step = advanceShot(motion, rules.shotFlightTicks - 1, inputs.copy(shots = inputs.shots + 1))
            step.copy(state = AthleteMotion.ballAction(step.state, shooter, BallAction.Shooting),
              events = released :: step.events)
          case None if penalty.remainingTicks <= 1 => resolve(MatchEvent.PenaltyExpired(shooter), inputs)
          case None =>
            Step(state.copy(phase = penalty.copy(remainingTicks = penalty.remainingTicks - 1)), Nil, inputs)

  private def seedForShot(seed: Long, shots: Long): Long = seed ^ (shots * 0x9e3779b97f4a7c15L)

  def advance(
      start: MatchState,
      initialBalls: MatchBalls,
      queued: Vector[(AthleteId, Control)],
      previous: Inputs,
      shotSeed: Long,
      rules: MatchRules = MatchRules.standard
  ): Step =
    var state = AthleteMotion.tick(start)
    var balls = initialBalls
    val events = List.newBuilder[MatchEvent]
    val step = start.clock.elapsed.value + 1
    var contacts = previous.contacts.filter(_._2.expiresAt >= step)
    var flightInputs = previous.flight
    var shots = previous.shots
    var passTarget = previous.passTarget.filter(_._2 >= step)
    var shotUntil = previous.shotUntil.filter(_ >= step)
    var catchRetryAt = previous.catchRetryAt.filter(_._2 > step)
    var attackClock = start.attackClock
    var thrower = Option.empty[AthleteId]
    val roster = state.setup.roster

    queued.foreach { case (id, control) =>
      val athlete = roster.find(_.id == id).get
      def reject(reason: ActionRejection): Unit =
        events += MatchEvent.ControlRejected(id, reason)
      def possession(next: QuaffleState, outcome: QuafflePossessionOutcome): Unit =
        balls = balls.copy(quaffle = next)
        events += MatchEvent.QuafflePossessionChanged(outcome)
      def ownBall(): Unit =
        if attackClock.forall(_.side != athlete.side) then attackClock = Some(rules.attackFor(athlete.side))
      control match
        case Control.Fly(input) => flightInputs = flightInputs.updated(id, input)
        case Control.Brace(active) =>
          val entry = state.athletes(id)
          val mode = if active then MovementMode.Bracing else MovementMode.Normal
          state = state.copy(athletes = state.athletes.updated(id, entry.copy(movement = mode)))
        case Control.Catch(CatchTarget.Quaffle) =>
          balls.quaffle match
            case loose @ QuaffleState.Free(motion) if !catchRetryAt.contains(id) =>
              val inReach = catchQuaffle(state, athlete, loose)
              if inReach && rules.securesCatch(state, athlete, motion, shotSeed, step) then
                passTarget = None
                shotUntil = None
                ownBall()
                possession(QuaffleState.HeldBy(id), QuafflePossessionOutcome.Caught(id))
                state = AthleteMotion.ballAction(state, id, BallAction.Securing)
              else
                state = AthleteMotion.ballAction(state, id, BallAction.Reaching)
                if inReach then
                  catchRetryAt = catchRetryAt.updated(id, step + rules.catchRetryTicks)
                  events += MatchEvent.QuaffleCatchDisrupted(id)
            case _ => ()
        case Control.Shoot(aim) =>
          if balls.quaffle != QuaffleState.HeldBy(id) then reject(ActionRejection.NotBallHolder)
          else if attackClock.exists(_.remainingTicks == 0) then reject(ActionRejection.TemporarilyUnavailable)
          else
            val motion = shoot(state, athlete, aim, seedForShot(shotSeed, shots))
            shots += 1
            shotUntil = Some(step + rules.shotFlightTicks - 1)
            thrower = Some(id)
            possession(QuaffleState.Free(motion), QuafflePossessionOutcome.Released(id, motion))
            state = AthleteMotion.ballAction(state, id, BallAction.Shooting)
        case Control.Pass(receiver) =>
          if balls.quaffle != QuaffleState.HeldBy(id) then reject(ActionRejection.NotBallHolder)
          else if !roster.exists(mate => mate.id == receiver && mate.id != id && mate.side == athlete.side) then
            reject(ActionRejection.AthleteNotOnPlayersSide)
          else
            val motion = pass(state, athlete, receiver)
            passTarget = Some(receiver -> (step + 2 * ticksPerSecond))
            thrower = Some(id)
            possession(QuaffleState.Free(motion), QuafflePossessionOutcome.Released(id, motion))
            state = AthleteMotion.ballAction(state, id, BallAction.Passing)
            events += MatchEvent.QuafflePassed(id, receiver)
        case Control.SwitchAthlete(_) => ()
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
    val afterMove = state
    val touching = Set.newBuilder[(AthleteId, AthleteId)]
    for
      (first, index) <- roster.zipWithIndex
      second <- roster.drop(index + 1)
    do
      val pair = first.id -> second.id
      def offset(value: MatchState): Vec3 =
        value.athletes(second.id).flight.position.value - value.athletes(first.id).flight.position.value
      val relative = offset(afterMove)
      val last = offset(before)
      val change = relative - last
      val fraction = clamp(-last.dot(change) / math.max(change.dot(change), 1e-9), 0, 1)
      val actualContact = (last + change * fraction).length <= 1.45
      if relative.length <= 1.65 then touching += pair
      if actualContact && !previous.touching.contains(pair) && state.phase.isInstanceOf[MatchPhase.InPlay] then
        val relevantContacts = if first.side == second.side then Map.empty else contacts
        val (initiator, opponent, kind) = contactInitiative(state, first, second, relative, relevantContacts)
        events += MatchEvent.ContactResolved(initiator.id, opponent.id, kind)
        val impact = contact(initiator, state.athletes(initiator.id), opponent, state.athletes(opponent.id), kind)
        val (updated, updatedBalls, contactEvents) = rules.resolveContact(state, balls, initiator, opponent, kind, impact)
        state = updated
        balls = updatedBalls
        events ++= contactEvents
        if first.side != second.side then contacts = contacts - first.id - second.id

    state.phase match
      case MatchPhase.InPlay(_) =>
        state = state.copy(phase = MatchPhase.InPlay(balls))
        balls.quaffle match
          case QuaffleState.Free(motion) =>
            advanceBall(motion, before, state, thrower) match
              case QuaffleRules.BallStep.Scored(side) =>
                state = state.copy(score = MatchRules.addGoal(state.score, side),
                  phase = MatchPhase.RestartPending(
                    rules.endRestart(MatchRules.opponent(side), MatchEvent.QuaffleGoal(side)), balls.special))
                events += MatchEvent.QuaffleGoal(side)
              case QuaffleRules.BallStep.Blocked(by, next) =>
                shotUntil = None
                state = state.copy(phase = MatchPhase.InPlay(balls.copy(quaffle = QuaffleState.Free(next))))
                events += MatchEvent.QuaffleBlocked(by)
              case QuaffleRules.BallStep.Moved(next, hitBoundary) =>
                if hitBoundary || shotUntil.contains(step) then shotUntil = None
                state = state.copy(phase = MatchPhase.InPlay(balls.copy(quaffle = QuaffleState.Free(next))))
          case QuaffleState.HeldBy(_) => ()
      case _ => ()
    val penaltyAwarded = state.phase match
      case MatchPhase.RestartPending(Restart.PenaltyThrow(_, _), _) => true
      case _ => false
    val elapsed = PlayTick(step)
    if elapsed.value == state.clock.durationTicks && !penaltyAwarded then
      state = state.copy(phase = MatchPhase.Finished(FinishReason.TimeExpired))
      events += MatchEvent.MatchEnded(FinishReason.TimeExpired)
    val remainingAttack = state.phase match
      case MatchPhase.InPlay(_) =>
        attackClock.map { clock =>
          val remaining = math.max(0L, clock.remainingTicks - 1)
          if remaining == 0 && shotUntil.isEmpty then
            state = state.copy(phase = MatchPhase.RestartPending(
              rules.endRestart(MatchRules.opponent(clock.side), MatchEvent.AttackExpired(clock.side)), balls.special))
            events += MatchEvent.AttackExpired(clock.side)
          clock.copy(remainingTicks = remaining)
        }
      case _ => None
    val liveAttack = state.phase match
      case MatchPhase.InPlay(_) => remainingAttack
      case _ => None
    Step(state.copy(clock = state.clock.copy(elapsed = elapsed), attackClock = liveAttack), events.result(),
      Inputs(flightInputs, touching.result(), contacts, shots, passTarget, shotUntil, previous.primaryDefender, catchRetryAt))

  private def contactInitiative(state: MatchState, first: Athlete, second: Athlete, relative: Vec3,
      contacts: Map[AthleteId, ContactAttempt]): (Athlete, Athlete, ContactKind) =
    contacts.get(first.id).map(attempt => (first, second, attempt.kind))
      .orElse(contacts.get(second.id).map(attempt => (second, first, attempt.kind)))
      .getOrElse {
        val toward = Direction.from(relative).getOrElse(Direction.east).value
        val firstVelocity = state.athletes(first.id).flight.velocity.value
        val secondVelocity = state.athletes(second.id).flight.velocity.value
        if (firstVelocity - secondVelocity).dot(toward) >= 0 &&
            firstVelocity.dot(toward) >= -secondVelocity.dot(toward) then
          (first, second, ContactKind.Incidental)
        else (second, first, ContactKind.Incidental)
      }
