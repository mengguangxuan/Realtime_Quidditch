package services.matches.logic

import java.util.concurrent.ThreadLocalRandom
import services.matches.objects.*
import MatchSimulation.{Inputs, begin, initialAthlete, resume, ticksPerSecond}

final class MatchSession private (initial: MatchState, shotSeed: Long, recordFrame: TimelineFrame => Unit):
  private val opening = TimelineFrame(SimulationTick(0), initial, Nil)
  recordFrame(opening)
  private var timeline = MatchTimeline(initial.id, Vector(opening))
  private var pending = Vector.empty[(PlayerId, Control)]
  private var nextControls = Option.empty[List[ControlAssignment]]
  private var inputs = Inputs()
  private var restartTicks = 0

  def latest: TimelineFrame = synchronized(timeline.frames.last)

  def frame(tick: SimulationTick): Option[TimelineFrame] = synchronized {
    Option.when(tick.value >= 0 && tick.value < timeline.frames.size)(timeline.frames(tick.value.toInt))
  }

  def start(): Either[ActionRejection, TimelineFrame] = synchronized {
    latest.state.phase match
      case MatchPhase.Ready => Right(append(begin(latest.state), Nil))
      case _ => Left(ActionRejection.TemporarilyUnavailable)
  }

  def submit(player: PlayerId, control: Control): Either[ActionRejection, SimulationTick] = synchronized {
    val state = latest.state
    for
      _ <- PlayerControl.human(state.setup, player)
      assignment <- nextControls.getOrElse(state.controls).find(_.player == player)
        .toRight(ActionRejection.PlayerNotInMatch)
      _ <- Either.cond(assignment.allowedControls.contains(control.kind), (),
        ActionRejection.ControlNotAllowed(control.kind))
      _ <- state.phase match
        case MatchPhase.InPlay(_) => Right(())
        case _ => Left(ActionRejection.MatchNotInPlay)
      _ <- Either.cond(pending.size < 256, (), ActionRejection.TemporarilyUnavailable)
    yield
      pending :+= (player -> control)
      SimulationTick(latest.tick.value + 1)
  }

  def switchAthlete(player: PlayerId, athlete: AthleteId): Either[ActionRejection, SimulationTick] = synchronized {
    val state = latest.state
    state.phase match
      case MatchPhase.InPlay(_) =>
        val previous = nextControls.getOrElse(state.controls)
        PlayerControl.switchTo(state.setup, previous, player, athlete).map { next =>
          val moved = next.filterNot(previous.contains).map(_.player).toSet
          pending = pending.filterNot(entry => moved.contains(entry._1))
          nextControls = Some(next)
          SimulationTick(latest.tick.value + 1)
        }
      case _ => Left(ActionRejection.MatchNotInPlay)
  }

  def advance(): TimelineFrame = synchronized {
    val state = latest.state
    state.phase match
      case MatchPhase.InPlay(balls) =>
        val assignments = nextControls.getOrElse(state.controls)
        val switched = assignments.filterNot(state.controls.contains)
        val previous = state.controls.map(assignment => assignment.player -> assignment.athlete).toMap
        val affected = switched.flatMap(entry => List(previous(entry.player), entry.athlete)).toSet
        val events = switched.map(entry => MatchEvent.ControlChanged(entry.player, previous(entry.player), entry.athlete))
        val active = state.copy(controls = assignments)
        val athleteByPlayer = assignments.map(assignment => assignment.player -> assignment.athlete).toMap
        val queued = pending.map { case (player, control) => athleteByPlayer(player) -> control }
        val computerPlayers = active.setup.players.filter(_.kind == PlayerKind.Computer).map(_.id).toSet
        val computer = assignments.filter(assignment => computerPlayers.contains(assignment.player))
          .flatMap(assignment => ComputerPilot.controls(active, balls, assignment).map(assignment.athlete -> _))
        val currentInputs = inputs.copy(flight = inputs.flight -- affected, contacts = inputs.contacts -- affected)
        val step = MatchSimulation.advance(active, balls, queued ++ computer, currentInputs, shotSeed)
        val saved = append(step.state, events ++ step.events)
        pending = Vector.empty
        nextControls = None
        inputs = step.inputs
        restartTicks = 0
        saved
      case MatchPhase.RestartPending(restart, special) =>
        if restartTicks + 1 >= ticksPerSecond then
          val saved = append(resume(state, restart, special), Nil)
          inputs = Inputs(shots = inputs.shots)
          saved
        else
          val saved = append(state, Nil)
          restartTicks += 1
          saved
      case MatchPhase.Ready | MatchPhase.Finished(_) => latest
  }

  private def append(state: MatchState, events: List[MatchEvent]): TimelineFrame =
    val saved = TimelineFrame(SimulationTick(timeline.frames.last.tick.value + 1), state, events)
    recordFrame(saved)
    timeline = timeline.copy(frames = timeline.frames :+ saved)
    saved

object MatchSession:
  def create(id: MatchId, setup: MatchSetup, durationTicks: Long,
      shotSeed: Long = ThreadLocalRandom.current().nextLong(),
      recordFrame: TimelineFrame => Unit = _ => ()): Either[String, MatchSession] =
    val ids = setup.roster.map(_.id)
    val expectedSides = Set(Side.Home, Side.Away)
    val assigned = setup.assignments.map(_.athlete)
    val players = setup.players.map(_.id)
    val controllers = setup.assignments.map(_.player)
    val sides = setup.players.map(player => player.id -> player.side).toMap
    val athleteSides = setup.roster.map(athlete => athlete.id -> athlete.side).toMap
    val valid = ids.size == 2 && ids.distinct.size == 2 && setup.roster.map(_.side).toSet == expectedSides &&
      setup.roles.keySet == ids.toSet && assigned.size == 2 && assigned.toSet == ids.toSet &&
      players.size == 2 && players.distinct.size == 2 && controllers.toSet == players.toSet &&
      setup.assignments.forall(assignment => sides.get(assignment.player) == athleteSides.get(assignment.athlete))
    for
      _ <- Either.cond(valid, (), "A 1v1 match needs one athlete on each side, with unique IDs and assignments")
      _ <- Either.cond(durationTicks > 0, (), "Match duration must be positive")
      _ <- Either.cond(setup.balls.bludgers.isEmpty && setup.balls.snitch.isEmpty, (), "The prototype supports the Quaffle")
    yield
      val athletes = setup.roster.map(athlete => athlete.id -> initialAthlete(athlete.side)).toMap
      new MatchSession(MatchState(id, setup, setup.assignments, athletes, Score(0, 0), MatchClock(PlayTick(0), durationTicks),
        Map.empty, MatchPhase.Ready), shotSeed, recordFrame)

  def demo(computerOpponent: Boolean = false,
      shotSeed: Long = ThreadLocalRandom.current().nextLong(),
      computerHome: Boolean = false, id: MatchId = MatchId("demo"),
      recordFrame: TimelineFrame => Unit = _ => ()): MatchSession =
    def athlete(id: Int, name: String, side: Side, speed: Int): Athlete =
      val average = Rating(70)
      Athlete(AthleteId(id), name, side, AthleteRatings(
        FlightRatings(Rating(speed), average, average, average),
        PhysicalRatings(average, average, average),
        BallControlRatings(average, average, average),
        ThrowingRatings(average, average, Rating(78), Rating(80), average),
        AwarenessRatings(average, average, average, average),
        GoalkeepingRatings(average, average, average, average)
      ))
    val home = athlete(1, "Home Chaser", Side.Home, 85)
    val away = athlete(2, "Away Chaser", Side.Away, if computerHome && computerOpponent then 85 else 65)
    val allowed = ControlKind.values.toSet - ControlKind.StrikeBludger
    val homePlayer = Player(PlayerId("home"), Side.Home, if computerHome then PlayerKind.Computer else PlayerKind.Human)
    val awayPlayer = Player(PlayerId("away"), Side.Away, if computerOpponent then PlayerKind.Computer else PlayerKind.Human)
    val setup = MatchSetup(List(home, away), List(homePlayer, awayPlayer),
      Map(home.id -> AthleteRole.Chaser, away.id -> AthleteRole.Chaser),
      List(ControlAssignment(homePlayer.id, home.id, allowed), ControlAssignment(awayPlayer.id, away.id, allowed)),
      BallSetup(BallPhysics(.15, .6, .002, .0001), Map.empty, None))
    create(id, setup, 180L * ticksPerSecond, shotSeed, recordFrame)
      .fold(error => throw IllegalStateException(error), identity)
