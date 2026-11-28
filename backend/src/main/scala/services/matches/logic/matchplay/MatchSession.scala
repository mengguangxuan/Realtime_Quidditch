package services.matches.logic

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import services.matches.objects.*
import MatchSimulation.{Inputs, begin, formation, resume, ticksPerSecond}

private[matches] final class MatchSession private (initial: MatchState, shotSeed: Long, recordFrame: TimelineFrame => Unit,
    rules: MatchRules):
  private val opening = TimelineFrame(SimulationTick(0), initial, Nil)
  recordFrame(opening)
  private var timeline = MatchTimeline(initial.id, Vector(opening))
  private var pending = Vector.empty[(AthleteId, Control)]
  private var nextControls = Option.empty[List[ControlAssignment]]
  private var inputs = Inputs()
  private var restartTicks = 0
  private var owner = Option.empty[String]

  private[matches] def mayStart(username: String): Boolean = synchronized {
    latest.state.phase match
      case MatchPhase.Finished(_) => true
      case _ => owner.forall(_ == username)
  }

  private[matches] def startFor(username: Option[String], lineup: Option[MatchSetup])
      : Either[String | ActionRejection, TimelineFrame] = synchronized {
    if username.exists(name => !mayStart(name)) then Left("match_unavailable")
    else start(lineup).map { frame =>
      if username.nonEmpty then owner = username
      frame
    }
  }

  private[matches] def submitFor(username: String, control: Control): Either[String | ActionRejection, SimulationTick] =
    synchronized {
      if owner.contains(username) then submit(PlayerId("home"), control)
      else Left("match_unavailable")
    }

  private[matches] def endFor(username: String, id: MatchId): Either[String | ActionRejection, TimelineFrame] =
    synchronized {
      val current = latest
      if current.state.id != id then Left(ActionRejection.TemporarilyUnavailable)
      else if !owner.contains(username) then Left("match_unavailable")
      else current.state.phase match
        case MatchPhase.Ready => Left(ActionRejection.MatchNotInPlay)
        case MatchPhase.Finished(_) => Right(current)
        case _ =>
          val reason = FinishReason.EndedByUser
          val finished = current.state.copy(phase = MatchPhase.Finished(reason), attackClock = None)
          Right(append(finished, List(MatchEvent.MatchEnded(reason))))
    }

  def latest: TimelineFrame = synchronized(timeline.frames.last)

  def frame(tick: SimulationTick): Option[TimelineFrame] = synchronized {
    Option.when(tick.value >= 0 && tick.value < timeline.frames.size)(timeline.frames(tick.value.toInt))
  }

  def start(lineup: Option[MatchSetup] = None): Either[ActionRejection, TimelineFrame] = synchronized {
    latest.state.phase match
      case MatchPhase.Ready =>
        val state = lineup.fold(latest.state)(setup => latest.state.copy(
          setup = setup, controls = setup.assignments,
          athletes = formation(setup.roster, setup.startingPositions.getOrElse(Map.empty))))
        Right(append(begin(state), Nil))
      case MatchPhase.Finished(_) if lineup.nonEmpty =>
        val next = initial.copy(id = MatchId(UUID.randomUUID().toString))
        val first = TimelineFrame(SimulationTick(0), next, Nil)
        recordFrame(first)
        timeline = MatchTimeline(next.id, Vector(first))
        owner = None
        pending = Vector.empty
        nextControls = None
        inputs = Inputs()
        restartTicks = 0
        start(lineup)
      case _ => Left(ActionRejection.TemporarilyUnavailable)
  }

  def submit(player: PlayerId, control: Control): Either[ActionRejection, SimulationTick] = synchronized {
    val state = latest.state
    val checked = for
      _ <- PlayerControl.human(state.setup, player)
      assignment <- nextControls.getOrElse(state.controls).find(_.player == player)
        .toRight(ActionRejection.PlayerNotInMatch)
      _ <- Either.cond(assignment.allowedControls.contains(control.kind), (),
        ActionRejection.ControlNotAllowed(control.kind))
      _ <- state.phase match
        case MatchPhase.InPlay(_) => Right(())
        case penalty: MatchPhase.Penalty
            if control.kind == ControlKind.Shoot && rules.canTakePenalty(penalty, assignment.athlete) => Right(())
        case _ => Left(ActionRejection.MatchNotInPlay)
      _ <- Either.cond(pending.size < 256, (), ActionRejection.TemporarilyUnavailable)
    yield assignment
    checked.flatMap { assignment =>
      control match
        case Control.SwitchAthlete(target) => switchAthlete(player, target)
        case _ =>
          pending :+= (assignment.athlete -> control)
          Right(SimulationTick(latest.tick.value + 1))
    }
  }

  def switchAthlete(player: PlayerId, athlete: AthleteId): Either[ActionRejection, SimulationTick] = synchronized {
    val state = latest.state
    state.phase match
      case MatchPhase.InPlay(_) =>
        val previous = nextControls.getOrElse(state.controls)
        PlayerControl.switchTo(state.setup, previous, player, athlete).map { next =>
          val moved = next.filterNot(previous.contains).map(_.athlete).toSet
          pending = pending.filterNot { case (athlete, control) =>
            moved.contains(athlete) && control.kind == ControlKind.Fly
          }
          nextControls = Some(next)
          SimulationTick(latest.tick.value + 1)
        }
      case _ => Left(ActionRejection.MatchNotInPlay)
  }

  def advance(): TimelineFrame = synchronized {
    val state = latest.state
    state.phase match
      case penalty @ MatchPhase.Penalty(_, side, shooter, _) =>
        val computerShoots = rules.canTakePenalty(penalty, shooter) && state.controls.exists(assignment =>
          assignment.athlete == shooter && assignment.allowedControls.contains(ControlKind.Shoot) &&
            state.setup.players.exists(player => player.id == assignment.player && player.kind == PlayerKind.Computer))
        val computer = if computerShoots then ComputerPilot.penaltyShot(state.athletes(shooter), side) else None
        val commands = pending ++ computer.map(shooter -> _)
        val step = MatchSimulation.advancePenalty(state, penalty, commands, inputs, shotSeed, rules)
        val saved = append(step.state, step.events)
        pending = Vector.empty
        inputs = step.inputs
        restartTicks = 0
        saved
      case MatchPhase.InPlay(balls) =>
        val assignments = nextControls.getOrElse(state.controls)
        val switched = assignments.filterNot(state.controls.contains)
        val previous = state.controls.map(assignment => assignment.player -> assignment.athlete).toMap
        val affected = switched.flatMap(entry => List(previous(entry.player), entry.athlete)).toSet
        val events = switched.map(entry => MatchEvent.ControlChanged(entry.player, previous(entry.player), entry.athlete))
        val active = state.copy(controls = assignments)
        val computerPlayers = active.setup.players.filter(_.kind == PlayerKind.Computer).map(_.id).toSet
        val passTarget = inputs.passTarget.filter(_._2 > state.clock.elapsed.value).map(_._1)
        val defense = TeamDefense.plan(active, balls.quaffle, inputs.primaryDefender)
        val computerAssignments = assignments.filter(assignment => computerPlayers.contains(assignment.player))
        val (support, supportSlots) = teamSupport(active, balls.quaffle, passTarget,
          computerAssignments.map(_.athlete).toSet, inputs.supportSlots)
        val computer = computerAssignments.flatMap(assignment =>
          ComputerPilot.controls(active, balls, assignment, passTarget, defense.get(assignment.athlete),
            support.get(assignment.athlete))
            .map(assignment.athlete -> _))
        val currentInputs = inputs.copy(flight = inputs.flight -- affected, contacts = inputs.contacts -- affected,
          primaryDefender = TeamDefense.marker(defense), supportSlots = supportSlots)
        val step = MatchSimulation.advance(active, balls, pending ++ computer, currentInputs, shotSeed, rules)
        val saved = append(step.state, events ++ step.events)
        pending = Vector.empty
        nextControls = None
        inputs = step.inputs
        restartTicks = 0
        saved
      case MatchPhase.RestartPending(restart, special) =>
        if restartTicks + 1 >= rules.restartTicks then
          val resumed = resume(state, restart, special, rules)
          val before = state.controls.map(entry => entry.player -> entry.athlete).toMap
          val switched = resumed.controls.filter(entry => before(entry.player) != entry.athlete)
          val events = switched.map(entry => MatchEvent.ControlChanged(entry.player, before(entry.player), entry.athlete))
          val saved = append(resumed, events)
          inputs = Inputs(shots = inputs.shots)
          saved
        else
          val saved = append(AthleteMotion.tick(state), Nil)
          restartTicks += 1
          saved
      case MatchPhase.Ready | MatchPhase.Finished(_) => latest
  }

  private def append(state: MatchState, events: List[MatchEvent]): TimelineFrame =
    val saved = TimelineFrame(SimulationTick(timeline.frames.last.tick.value + 1), state, events)
    recordFrame(saved)
    timeline = timeline.copy(frames = timeline.frames :+ saved)
    saved

private[matches] object MatchSession:
  def create(id: MatchId, setup: MatchSetup, durationTicks: Long,
      shotSeed: Long = ThreadLocalRandom.current().nextLong(),
      recordFrame: TimelineFrame => Unit = _ => (), rules: MatchRules = MatchRules.standard): Either[String, MatchSession] =
    val ids = setup.roster.map(_.id)
    val expectedSides = Set(Side.Home, Side.Away)
    val assigned = setup.assignments.map(_.athlete)
    val players = setup.players.map(_.id)
    val controllers = setup.assignments.map(_.player)
    val sides = setup.players.map(player => player.id -> player.side).toMap
    val athleteSides = setup.roster.map(athlete => athlete.id -> athlete.side).toMap
    val valid = ids.distinct.size == ids.size &&
      expectedSides.forall(side => setup.roster.count(_.side == side) * 2 == ids.size) &&
      ids.nonEmpty && setup.roster.map(_.side).toSet == expectedSides &&
      setup.roles.keySet == ids.toSet && assigned.size == ids.size && assigned.toSet == ids.toSet &&
      players.size == ids.size && players.distinct.size == players.size && controllers.toSet == players.toSet &&
      setup.assignments.forall(assignment => sides.get(assignment.player) == athleteSides.get(assignment.athlete))
    for
      _ <- Either.cond(valid, (), "A match needs equal teams and unique athlete and player assignments")
      _ <- Either.cond(durationTicks > 0, (), "Match duration must be positive")
      _ <- Either.cond(setup.balls.bludgers.isEmpty && setup.balls.snitch.isEmpty, (), "The prototype supports the Quaffle")
      _ <- setup.startingPositions.fold[Either[String, Unit]](Right(()))(MatchSimulation.startingPositionsValid(setup.roster, _))
    yield
      val athletes = formation(setup.roster, setup.startingPositions.getOrElse(Map.empty))
      new MatchSession(MatchState(id, setup, setup.assignments, athletes, Score(0, 0), MatchClock(PlayTick(0), durationTicks),
        Map.empty, MatchPhase.Ready), shotSeed, recordFrame, rules)

  def demo(cards: List[Athlete], computerOpponent: Boolean = false,
      shotSeed: Long = ThreadLocalRandom.current().nextLong(),
      computerHome: Boolean = false, id: MatchId = MatchId("demo"),
      recordFrame: TimelineFrame => Unit = _ => (), teamSize: Int = 1): MatchSession =
    val setup = demoSetup(cards, computerOpponent, computerHome, teamSize, None)
      .fold(error => throw IllegalArgumentException(error), identity)
    create(id, setup, 180L * ticksPerSecond, shotSeed, recordFrame)
      .fold(error => throw IllegalStateException(error), identity)

  val demoTeamSizes = List(1, 2, 7)
  private val expandedCards = (5 to 18).map(AthleteId(_)).toSet
  private val sevenAsideIds = (List(1, 3) ++ (5 to 9) ++ (12 to 18)).map(AthleteId(_)).toSet
  private val sevenAsideReserves = Set(AthleteId(10), AthleteId(11))

  private[matches] def demoReserves(cards: List[Athlete], teamSize: Int): List[Athlete] =
    if teamSize == 7 then cards.filter(athlete => sevenAsideReserves.contains(athlete.id)).sortBy(_.id.value)
    else Nil

  private[matches] def demoKeeper(side: Side, teamSize: Int): Option[AthleteId] =
    Option.when(teamSize == 7)(AthleteId(if side == Side.Home then 3 else 13))

  private[matches] def demoCards(cards: List[Athlete], teamSize: Int): List[Athlete] =
    val available = if teamSize == 7 then cards.filter(athlete => sevenAsideIds.contains(athlete.id))
      else cards.filterNot(athlete => expandedCards.contains(athlete.id))
    available.sortBy(_.id.value)

  def demoSetup(cards: List[Athlete], computerOpponent: Boolean, computerHome: Boolean,
      teamSize: Int, selection: Option[AthleteId], homeTactics: TeamTactics = TeamTactics.default,
      startingPositions: Option[Map[AthleteId, Position]] = None, swapTeams: Boolean = false,
      starters: Option[List[AthleteId]] = None): Either[String, MatchSetup] =
    if !demoTeamSizes.contains(teamSize) then return Left("Team size must be 1, 2 or 7")
    if teamSize != 7 && (swapTeams || starters.nonEmpty) then return Left("Team and lineup selection requires 7v7")
    val available = demoCards(cards, teamSize)
    val selectedSide = if swapTeams then Side.Away else Side.Home
    val opponentSide = MatchRules.opponent(selectedSide)
    val homeKeeper = demoKeeper(selectedSide, teamSize)
    val awayKeeper = demoKeeper(opponentSide, teamSize)
    val homeOptions = (available ++ demoReserves(cards, teamSize)).filter(_.side == selectedSide).sortBy(_.id.value)
    if starters.exists(ids => ids.size != teamSize || ids.distinct.size != teamSize ||
        !ids.forall(id => homeOptions.exists(_.id == id)) || !homeKeeper.forall(ids.contains)) then
      return Left("Choose seven distinct starters from your team, including the keeper")
    val home = starters.fold(available.filter(_.side == selectedSide))(ids => homeOptions.filter(a => ids.contains(a.id)))
    if selection.exists(id => !home.exists(_.id == id)) then return Left("Choose a home athlete card")
    val homeCards = home.sortBy(athlete => (homeKeeper.contains(athlete.id), !selection.contains(athlete.id)))
      .take(teamSize).map(_.copy(side = Side.Home))
    val awayCards = available.filter(_.side == opponentSide).sortBy(athlete => awayKeeper.contains(athlete.id))
      .take(teamSize).map(_.copy(side = Side.Away))
    if homeCards.size != teamSize || awayCards.size != teamSize then return Left(s"Each side needs $teamSize athlete cards")
    val roster = homeCards.zip(awayCards).flatMap((home, away) => List(home, away))
    val positions = startingPositions.filter(_.nonEmpty)
    val allowed = ControlKind.values.toSet - ControlKind.StrikeBludger
    val homePlayer = Player(PlayerId("home"), Side.Home, if computerHome then PlayerKind.Computer else PlayerKind.Human)
    val awayPlayer = Player(PlayerId("away"), Side.Away, if computerOpponent then PlayerKind.Computer else PlayerKind.Human)
    val homePilot = homeCards.find(athlete => selection.contains(athlete.id)).getOrElse(homeCards.head)
    val initialPilots = List(homePilot, awayCards.head)
    val supporting = roster.filterNot(initialPilots.contains)
    val players = List(homePlayer, awayPlayer) ++ supporting.map(athlete =>
      Player(PlayerId(s"support-${athlete.id.value}"), athlete.side, PlayerKind.Computer))
    positions.fold[Either[String, Unit]](Right(()))(MatchSimulation.startingPositionsValid(roster, _)).map { _ =>
      MatchSetup(roster, players,
        roster.map { athlete =>
          val role = if homeKeeper.contains(athlete.id) || awayKeeper.contains(athlete.id) then AthleteRole.Keeper
            else AthleteRole.Chaser
          athlete.id -> role
        }.toMap,
        players.zip(initialPilots ++ supporting).map((player, athlete) => ControlAssignment(player.id, athlete.id, allowed)),
        BallSetup(BallPhysics(.15, .6, .002, .0001), Map.empty, None),
        Some(MatchTactics(homeTactics, TeamTactics.default)), positions, Option.when(swapTeams)(true))
    }
