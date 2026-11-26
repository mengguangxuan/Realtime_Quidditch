package services.matches.logic

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import scala.util.Using
import services.matches.api.ApplyControlMessage
import services.matches.objects.*
import services.matches.routes.MatchRoutes
import services.matches.tables.athletecard.{AthleteCardTable, AthleteCardTableInitializer}
import services.matches.tables.timeline.{TimelineTable, TimelineTableInitializer}
import services.system.Database

object MatchChecks:
  private lazy val cards = Using.resource(DriverManager.getConnection("jdbc:sqlite::memory:")) { connection =>
    (AthleteCardTableInitializer.initialize(connection) *> AthleteCardTable.list(connection)).unsafeRunSync()
  }

  def main(args: Array[String]): Unit =
    val matchSession = MatchSession.demo(cards, shotSeed = 42)
    assert(matchSession.latest.state.setup.roster == cards.take(2))
    val roster = MatchSession.demo(cards, teamSize = 2).latest.state.setup.roster
    assert(roster == cards.take(4))
    val sevenAside = MatchSession.demo(cards, computerOpponent = true, teamSize = 7).latest.state
    val sevenSetup = sevenAside.setup
    assert(sevenSetup.roster.groupBy(_.side).values.forall(_.size == 7))
    assert(sevenSetup.roster.filter(_.side == Side.Home).map(_.id.value) == List(1, 3, 5, 6, 7, 8, 9))
    assert(sevenSetup.roster.filter(_.side == Side.Away).map(_.id.value) == (12 to 18).toList)
    assert(sevenSetup.players.count(_.kind == PlayerKind.Human) == 1)
    assert(MatchSimulation.startingPositionsValid(sevenSetup.roster, Map.empty).isRight)
    assert(sevenAside.athletes.values.map(_.flight.position).toSet.size == 14)
    val ron = MatchSession.demoSetup(cards, true, false, 7, Some(AthleteId(3))).toOption.get
    assert(ron.roster.head.id == AthleteId(3))
    assert(MatchSession.demoSetup(cards, true, false, 7, Some(AthleteId(10))).isLeft)
    assert(roster.map(_.id).distinct.size == 4 && roster.map(_.ratings).distinct.size == 4)
    assert(roster.count(_.side == Side.Home) == 2 && roster.count(_.side == Side.Away) == 2)
    val home = PlayerId("home")
    val homeAthlete = AthleteId(1)
    assert(matchSession.latest.tick == SimulationTick(0))
    assert(matchSession.frame(SimulationTick(-1)).isEmpty)
    assert(matchSession.start().isRight)
    assert(matchSession.submit(PlayerId("guest"), Control.Charge) == Left(ActionRejection.PlayerNotInMatch))
    assert(matchSession.switchAthlete(home, AthleteId(2)) == Left(ActionRejection.AthleteNotOnPlayersSide))
    val catchCommand = ApplyControlMessage(home, Control.Catch(CatchTarget.Quaffle))
    assert(catchCommand.plan(matchSession).isRight)
    val missed = matchSession.advance()
    assert(missed.events.isEmpty)
    assert(missed.state.athletes(homeAthlete).pose.flatMap(_.ballAction).exists(_.kind == BallAction.Reaching))
    for _ <- 1 to 4 do matchSession.advance()
    assert(matchSession.latest.state.athletes(homeAthlete).pose.isEmpty)

    def steer(player: PlayerId, athlete: AthleteId, target: Vec3): Unit =
      for _ <- 1 to 150 do
        val flight = matchSession.latest.state.athletes(athlete).flight
        val adjust = (target - flight.position.value) * .24 - flight.velocity.value * .075
        def axis(value: Double): AxisInput = AxisInput(FlightRules.clamp(value, -1, 1))
        assert(matchSession.submit(player, Control.Fly(FlightInput(axis(adjust.x), axis(adjust.y),
          axis(adjust.z), false))).isRight)
        matchSession.advance()
      assert((matchSession.latest.state.athletes(athlete).flight.position.value - target).length < .4)

    steer(home, homeAthlete, Vec3(0, 0, 1.5))
    assert(decode[ApplyControlMessage](catchCommand.asJson.noSpaces) == Right(catchCommand))
    assert(catchCommand.plan(matchSession).isRight)
    val caught = matchSession.advance()
    assert(caught.events.contains(MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Caught(homeAthlete))))
    val catchingAthlete = caught.state.athletes(homeAthlete)
    assert(catchingAthlete.pose.flatMap(_.ballAction).exists(_.kind == BallAction.Securing))
    assert(catchingAthlete.asJson.mapObject(_.remove("pose")).as[AthleteState] ==
      Right(catchingAthlete.copy(pose = None)))
    assert(decode[TimelineFrame](caught.asJson.noSpaces) == Right(caught))

    steer(home, homeAthlete, Vec3(36, 7.1, 10.0))
    val position = matchSession.latest.state.athletes(homeAthlete).flight.position.value
    val direction = Direction.from(Vec3(44, 7.1, 10.45) - position).toOption.get
    assert(matchSession.submit(home, Control.Shoot(ShotAim(direction, ShotPower(1), None))).isRight)
    val released = matchSession.advance()
    assert(released.state.athletes(homeAthlete).pose.flatMap(_.ballAction).exists(_.kind == BallAction.Shooting))
    assert(released.events.exists {
      case MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Released(`homeAthlete`, _)) => true
      case _ => false
    })
    val scoringFrame = Iterator.fill(40)(()).map(_ => matchSession.advance())
      .find(_.events.contains(MatchEvent.QuaffleGoal(Side.Home))).get
    assert(scoringFrame.state.score == Score(10, 0))
    assert(matchSession.frame(scoringFrame.tick).contains(scoringFrame))
    val paused = matchSession.advance()
    assert(paused.tick.value == scoringFrame.tick.value + 1 && paused.state.clock == scoringFrame.state.clock)
    Iterator.continually(matchSession.advance()).take(21).foreach(_ => ())
    assert(matchSession.latest.state.phase match
      case MatchPhase.InPlay(MatchBalls(QuaffleState.HeldBy(AthleteId(2)), _)) => true
      case _ => false
    )

    val demo = matchSession.latest.state
    val carrier = demo.setup.roster.last
    val challenger = demo.setup.roster.head
    val live = MatchBalls(QuaffleState.HeldBy(carrier.id), SpecialBalls(Map.empty, None))
    def place(id: AthleteId, position: Vec3, velocity: Vec3, bracing: Boolean): AthleteState =
      demo.athletes(id).copy(
        flight = FlightState(Position(position), Velocity(velocity), Direction.west),
        stability = Stability(80),
        movement = if bracing then MovementMode.Bracing else MovementMode.Normal
      )
    val attacker = place(challenger.id, Vec3(0, -1, 7), Vec3(0, 5, 0), false)
    val holder = place(carrier.id, Vec3(0, 0, 7), Vec3.zero, false)
    val contactState = demo.copy(athletes = Map(challenger.id -> attacker, carrier.id -> holder))
    def resolveContact(state: MatchState, kind: ContactKind): (MatchState, MatchBalls, List[MatchEvent]) =
      val impact = FlightRules.contact(challenger, state.athletes(challenger.id),
        carrier, state.athletes(carrier.id), kind)
      MatchRules.standard.resolveContact(state, live, challenger, carrier, kind, impact)

    val (_, dropped, _) = resolveContact(contactState, ContactKind.Charge)
    assert(dropped.quaffle.isInstanceOf[QuaffleState.Free])
    val braced = contactState.copy(athletes = contactState.athletes.updated(carrier.id,
      holder.copy(movement = MovementMode.Bracing)))
    assert(resolveContact(braced, ContactKind.Charge)._2.quaffle == live.quaffle)
    val (fouled, _, events) = resolveContact(contactState, ContactKind.Grab)
    assert(fouled.fouls(challenger.id) == 1)
    assert(fouled.phase.isInstanceOf[MatchPhase.RestartPending])
    assert(events.exists {
      case MatchEvent.FoulCalled(Foul(_, _, FoulKind.Grabbing, _, _)) => true
      case _ => false
    })
    checkHttp()
    checkHistory()
    checkStartingFormation()
    checkRiderMovement(demo)
    checkShotsAndBlocks(demo)
    checkCatchPressure(demo)
    checkAttackPlanning()
    checkCompetitionRules(demo)
    checkDefensePlanning(demo)
    checkCoordinatedDefense()
    checkTeamPressureAssignments()
    checkTeamTactics()
    val solo = MatchSession.demo(cards, computerOpponent = true, shotSeed = 42)
    assert(solo.start().isRight)
    assert(solo.submit(PlayerId("away"), Control.Charge) == Left(ActionRejection.PlayerIsComputer))
    checkControlSwitch(solo.latest.state.setup)
    assert(Iterator.fill(900)(()).map(_ => solo.advance()).exists(_.state.score.away == 10))
    checkComputerMatch()
    println("Verified controls, catch, release, goal, restart, contact, foul, timeline and HTTP routes")

  private def checkCompetitionRules(demo: MatchState): Unit =
    val rules = MatchRules(attackSeconds = 1)
    val special = SpecialBalls(Map.empty, None)
    val home = demo.setup.roster.find(_.side == Side.Home).get
    val away = demo.setup.roster.find(_.side == Side.Away).get
    val held = MatchBalls(QuaffleState.HeldBy(home.id), special)
    val live = demo.copy(phase = MatchPhase.InPlay(held), attackClock = Some(MatchClock.Attack(Side.Home, 1)))
    val expired = MatchSimulation.advance(live, held, Vector.empty, MatchSimulation.Inputs(), 42, rules)
    assert(expired.events == List(MatchEvent.AttackExpired(Side.Home)))
    val restart = rules.endRestart(Side.Away, MatchEvent.AttackExpired(Side.Home))
    assert(expired.state.phase == MatchPhase.RestartPending(restart, special))
    val resumed = MatchSimulation.resume(expired.state, restart, special, rules)
    assert(resumed.attackClock.contains(MatchClock.Attack(Side.Away, 20)))
    assert(resumed.athletes(away.id).flight.position.value == Vec3(36, 0, 9))
    val tooLate = MatchSimulation.advance(live.copy(attackClock = Some(MatchClock.Attack(Side.Home, 0))),
      held, Vector(home.id -> Control.Shoot(ShotAim(Direction.east, ShotPower(1), None))),
      MatchSimulation.Inputs(), 42, rules)
    assert(tooLate.events == List(MatchEvent.ControlRejected(home.id, ActionRejection.TemporarilyUnavailable),
      MatchEvent.AttackExpired(Side.Home)))

    val highShot = QuaffleRules.free(Vec3(43, 0, 12), Vec3(25, 0, 0)).motion
    val movedAway = live.athletes.updated(away.id, live.athletes(away.id).copy(
      flight = MatchSimulation.initialAthlete(Side.Away).flight))
    val shooting = live.copy(athletes = movedAway)
    val flight = MatchSimulation.Inputs(shotUntil = Some(live.clock.elapsed.value + 4))
    val lastSecond = MatchSimulation.advance(shooting, held.copy(quaffle = QuaffleState.Free(
      highShot.copy(position = Position(Vec3(42, 0, 12))))), Vector.empty, flight, 42, rules)
    assert(lastSecond.state.attackClock.contains(MatchClock.Attack(Side.Home, 0)))
    val buzzerGoal = MatchSimulation.advance(shooting, held.copy(quaffle = QuaffleState.Free(highShot)),
      Vector.empty, flight, 42, rules)
    assert(buzzerGoal.events.contains(MatchEvent.QuaffleGoal(Side.Home)))

    val foul = Foul(away.id, home.id, FoulKind.RearCharge, Position(Vec3(31, 0, 12)), deniedClearChance = true)
    val penaltyRestart = Restart.PenaltyThrow(Side.Home, foul)
    assert(rules.foulRestart(Side.Home, foul, 1) == penaltyRestart)
    assert(rules.foulRestart(Side.Home, foul.copy(deniedClearChance = false), 2).isInstanceOf[Restart.PenaltyThrow])
    val penalty = MatchSimulation.resume(live, penaltyRestart, special, rules)
    val attempt = penalty.phase.asInstanceOf[MatchPhase.Penalty]
    assert(attempt.shooter == home.id && attempt.balls.quaffle == held.quaffle)
    assert(penalty.athletes(home.id).flight.position.value == Vec3(33, 0, 12))
    assert(penalty.attackClock.isEmpty)
    val wrongHolder: MatchPhase.Penalty =
      attempt.copy(balls = held.copy(quaffle = QuaffleState.HeldBy(away.id)))
    val shoot = Control.Shoot(ShotAim(Direction.east, ShotPower(1), None))
    val rejected = MatchSimulation.advancePenalty(penalty.copy(phase = wrongHolder), wrongHolder,
      Vector(home.id -> shoot, away.id -> shoot), MatchSimulation.Inputs(), 42, rules)
    assert(rejected.state.phase == wrongHolder.copy(remainingTicks = wrongHolder.remainingTicks - 1))
    assert(rejected.events.isEmpty)

    def resolve(ball: QuaffleState, state: MatchState = penalty): MatchSimulation.Step =
      val phase = attempt.copy(balls = held.copy(quaffle = ball), remainingTicks = 1)
      MatchSimulation.advancePenalty(state.copy(phase = phase), phase, Vector.empty, MatchSimulation.Inputs(), 42, rules)

    def checkOutcome(step: MatchSimulation.Step, outcome: MatchEvent): Unit =
      assert(step.events.contains(outcome))
      assert(step.state.clock == penalty.clock)
      assert(step.state.phase == MatchPhase.RestartPending(
        rules.endRestart(Side.Away, outcome), special))

    checkOutcome(resolve(held.quaffle), MatchEvent.PenaltyExpired(home.id))
    val miss = QuaffleRules.free(Vec3(33, 0, .2), Vec3(0, 0, -4))
    checkOutcome(resolve(miss), MatchEvent.PenaltyMissed(home.id))
    val blocked = QuaffleRules.free(Vec3(40, 0, 12), Vec3(30, 0, 0))
    checkOutcome(resolve(blocked), MatchEvent.PenaltyBlocked(home.id, away.id))
    val scored = resolve(QuaffleState.Free(highShot))
    checkOutcome(scored, MatchEvent.PenaltyScored(home.id))
    assert(scored.state.score.home == penalty.score.home + 10)
    assert(scoreFromEvents(penalty.score, scored.events) == scored.state.score)
    val finalClock = penalty.clock.copy(elapsed = PlayTick(penalty.clock.durationTicks))
    val finalPenalty = resolve(QuaffleState.Free(highShot), penalty.copy(clock = finalClock))
    assert(finalPenalty.state.phase == MatchPhase.Finished(FinishReason.TimeExpired))
    assert(finalPenalty.events.contains(MatchEvent.MatchEnded(FinishReason.TimeExpired)))

    val json = live.asJson.mapObject(_.remove("attackClock"))
    assert(json.as[MatchState] == Right(live.copy(attackClock = None)))

  private def scoreFromEvents(initial: Score, events: Iterable[MatchEvent]): Score =
    events.foldLeft(initial) {
      case (score, MatchEvent.QuaffleGoal(Side.Home)) => score.copy(home = score.home + 10)
      case (score, MatchEvent.QuaffleGoal(Side.Away)) => score.copy(away = score.away + 10)
      case (score, _) => score
    }

  private def checkComputerMatch(): Unit =
    val equalCards = cards.map(_.copy(ratings = cards.head.ratings))
    val session = MatchSession.demo(equalCards, computerOpponent = true, shotSeed = 42, computerHome = true)
    val setup = session.latest.state.setup
    assert(setup.players.forall(_.kind == PlayerKind.Computer))
    assert(setup.roster.map(_.ratings).distinct.size == 1)
    assert(session.start().isRight)
    setup.players.foreach { player =>
      assert(session.submit(player.id, Control.Charge) == Left(ActionRejection.PlayerIsComputer))
    }
    val frames = Iterator.fill((session.latest.state.clock.durationTicks * 2).toInt)(()).map(_ => session.advance())
      .takeWhile(frame => !frame.state.phase.isInstanceOf[MatchPhase.Finished]).toVector
    assert(session.latest.state.phase.isInstanceOf[MatchPhase.Finished])
    assert(setup.roster.forall { rider =>
      frames.exists(_.events.contains(
        MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Caught(rider.id))))
    })
    val events = frames.flatMap(_.events)
    val releases = events.count {
      case MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Released(_, _)) => true
      case _ => false
    }
    val blocks = events.count(_.isInstanceOf[MatchEvent.QuaffleBlocked])
    val attempts = events.count(_.isInstanceOf[MatchEvent.ContactAttempted])
    val contacts = events.count(_.isInstanceOf[MatchEvent.ContactResolved])
    assert(releases > 0)
    val score = session.latest.state.score
    assert(scoreFromEvents(Score(0, 0), events ++ session.latest.events) == score)
    println(s"CPU vs CPU (seed 42): ${score.home}-${score.away}, $releases releases, $blocks blocks, $attempts contact attempts, $contacts contacts")

  private def checkStartingFormation(): Unit =
    val primary = AthleteId(3)
    val teammate = AthleteId(1)
    val initialPosition = Position(Vec3(-21, -7, 7))
    val starting = Map(teammate -> initialPosition)
    def setup(positions: Map[AthleteId, Position]): Either[String, MatchSetup] =
      MatchSession.demoSetup(cards, true, true, 2, Some(primary), startingPositions = Some(positions))
    val arranged = setup(starting).toOption.get
    val session = MatchSession.demo(cards, teamSize = 2)
    val opening = session.start(Some(arranged)).toOption.get
    assert(opening.state.setup.startingPositions.contains(starting))
    assert(opening.state.athletes(teammate).flight.position == initialPosition)
    assert(opening.state.athletes(primary).flight.position == MatchSimulation.startingSlot(Side.Home, 0))
    assert(decode[TimelineFrame](opening.asJson.noSpaces) == Right(opening))
    assert(arranged.asJson.mapObject(_.remove("startingPositions")).as[MatchSetup] ==
      Right(arranged.copy(startingPositions = None)))
    assert(setup(Map(teammate -> MatchSimulation.startingSlot(Side.Home, 0))).left.toOption.contains("Starting players too close"))
    assert(setup(Map(teammate -> Position(Vec3(8, 0, 7)))).left.toOption.contains("Invalid starting position"))
    assert(setup(Map(teammate -> Position(Vec3(-34, 26, 7)))).left.toOption.contains("Invalid starting position"))
    assert(setup(Map(AthleteId(2) -> initialPosition)).left.toOption.contains("Invalid starting athlete"))

  private def checkRiderMovement(demo: MatchState): Unit =
    val rider = demo.setup.roster.head
    val facing = Direction.from(Vec3(0, 1, 0)).toOption.get
    val stationary = demo.athletes(rider.id).copy(
      flight = FlightState(Position(Vec3(0, 0, 7)), Velocity(Vec3.zero), facing)
    )
    val zero = AxisInput(0)
    val idle = FlightInput(zero, zero, zero, false, Some(zero))
    assert(decode[FlightInput](idle.asJson.noSpaces) == Right(idle))
    val directions = List(
      idle.copy(forward = AxisInput(1)) -> facing.value,
      idle.copy(forward = AxisInput(-1)) -> (facing.value * -1),
      idle.copy(strafe = AxisInput(1)) -> Vec3(1, 0, 0),
      idle.copy(strafe = AxisInput(-1)) -> Vec3(-1, 0, 0)
    )
    directions.foreach { case (input, expected) =>
      val moved = (1 to 5).foldLeft(stationary) { (state, _) =>
        FlightRules.moveAthlete(rider, state, Some(input), carrying = false)
      }
      assert(moved.flight.facing == facing)
      assert(moved.flight.velocity.value.dot(expected) > 0)
    }
    val turned = FlightRules.moveAthlete(rider, stationary,
      Some(idle.copy(turn = Some(AxisInput(1)))), carrying = false)
    assert(turned.flight.facing.value.x > 0 && turned.flight.velocity.value == Vec3.zero)
    val sprint = idle.copy(forward = AxisInput(1), sprint = true)
    val midfield = stationary.copy(flight = stationary.flight.copy(facing = Direction.east))
    val afterThreeSeconds = (1 to 3 * MatchSimulation.ticksPerSecond).foldLeft(midfield) { (state, _) =>
      FlightRules.moveAthlete(rider, state, Some(sprint), carrying = false)
    }
    assert(afterThreeSeconds.flight.position.value.x > 40)
    assert(afterThreeSeconds.flight.position.value.x < Pitch.alpine.halfLength - 5)
    val high = stationary.copy(flight = stationary.flight.copy(position = Position(Vec3(0, 0, 20))))
    val diving = (1 to 10).foldLeft(high) { (state, _) =>
      FlightRules.moveAthlete(rider, state, Some(idle.copy(climb = AxisInput(-1))), carrying = false)
    }
    assert(diving.pose.exists(_.diving))
    assert(!FlightRules.moveAthlete(rider, diving, Some(idle), carrying = false).pose.exists(_.diving))

  private def checkControlSwitch(setup: MatchSetup): Unit =
    val human = setup.players.head
    val first = setup.roster.head
    val teammate = first.copy(id = AthleteId(3))
    val teammatePlayer = Player(PlayerId("teammate"), human.side, PlayerKind.Computer)
    val controls = setup.assignments :+ ControlAssignment(teammatePlayer.id, teammate.id, Set(ControlKind.Fly))
    val team = setup.copy(roster = setup.roster :+ teammate, players = setup.players :+ teammatePlayer)
    val swapped = PlayerControl.switchTo(team, controls, human.id, teammate.id).toOption.get
    assert(swapped.find(_.player == human.id).contains(controls.head.copy(athlete = teammate.id)))
    assert(swapped.find(_.player == teammatePlayer.id).contains(controls.last.copy(athlete = first.id)))
    val humanTeammate = team.copy(players = setup.players :+ teammatePlayer.copy(kind = PlayerKind.Human))
    assert(PlayerControl.switchTo(humanTeammate, controls, human.id, teammate.id) ==
      Left(ActionRejection.AthleteControlledByAnotherPlayer))

  private def checkShotsAndBlocks(demo: MatchState): Unit =
    val home = demo.setup.roster.head
    val away = demo.setup.roster.last
    val aim = ShotAim(Direction.east, ShotPower(1), None)
    val settled = demo.athletes(home.id).copy(
      flight = FlightState(Position(Vec3(36, 0, 12)), Velocity(Vec3.zero), Direction.east),
      stability = Stability(100))
    val good = ShotAccuracy.standardDeviation(home, settled, aim)
    assert(ShotAccuracy.standardDeviation(home,
      settled.copy(flight = settled.flight.copy(position = Position(Vec3(5, 0, 12)))), aim) > good)
    assert(ShotAccuracy.standardDeviation(home,
      settled.copy(flight = settled.flight.copy(facing = Direction.west)), aim) > good)
    assert(ShotAccuracy.standardDeviation(home,
      settled.copy(flight = settled.flight.copy(velocity = Velocity(Vec3(0, 20, 0)))), aim) > good)
    val poorFinisher = home.copy(ratings = home.ratings.copy(
      throwing = home.ratings.throwing.copy(finishing = Rating(25))))
    assert(ShotAccuracy.standardDeviation(poorFinisher, settled, aim) > good)
    val seed = 42L
    def scatter(athlete: Athlete): Double =
      val random = new scala.util.Random(123)
      val directions = Vector.fill(400)(ShotAccuracy.direction(athlete, settled, aim, random.nextLong()).value)
      val angle = directions.map(value => math.acos(FlightRules.clamp(value.dot(aim.direction.value), -1, 1)))
      assert(angle.forall(_ <= ShotAccuracy.maxDeviation + 1e-9))
      assert(math.abs(directions.map(_.y).sum / directions.size) < .01)
      assert(math.abs(directions.map(_.z).sum / directions.size) < .01)
      angle.sum / angle.size
    assert(scatter(home) < scatter(poorFinisher))
    assert(ShotAccuracy.direction(home, settled, aim, seed) == ShotAccuracy.direction(home, settled, aim, seed))

    def withDefender(x: Double, y: Double): MatchState =
      val defender = demo.athletes(away.id).copy(
        flight = FlightState(Position(Vec3(x, y, 11.82)), Velocity(Vec3.zero), Direction.west))
      demo.copy(athletes = Map(home.id -> settled, away.id -> defender))

    val shot = QuaffleRules.free(Vec3(43, 0, 12), Vec3(34, 0, 0)).motion
    val clear = withDefender(43.9, 3)
    assert(QuaffleRules.advanceBall(shot, clear, clear, None) == QuaffleRules.BallStep.Scored(Side.Home))
    val blocking = withDefender(44.22, 0)
    QuaffleRules.advanceBall(shot, blocking, blocking, None) match
      case QuaffleRules.BallStep.Blocked(by, motion) =>
        assert(by == away.id && motion.velocity.value.x < 0 && motion.position.value.x < 44)
        assert(QuaffleRules.advanceBall(motion, blocking, blocking, None).isInstanceOf[QuaffleRules.BallStep.Moved])
      case other => throw AssertionError(s"Expected a deflection, found $other")
    val late = withDefender(45.4, 0)
    assert(QuaffleRules.advanceBall(shot, late, late, None) == QuaffleRules.BallStep.Scored(Side.Home))
    val live = MatchBalls(QuaffleState.Free(shot), SpecialBalls(Map.empty, None))
    val state = blocking.copy(phase = MatchPhase.InPlay(live))
    val step = MatchSimulation.advance(state, live, Vector.empty, MatchSimulation.Inputs(), seed)
    assert(step.events == List(MatchEvent.QuaffleBlocked(away.id)))
    assert(step.state.score == state.score)

  private def checkCatchPressure(demo: MatchState): Unit =
    val receiver = demo.setup.roster.find(_.side == Side.Home).get
    val defender = demo.setup.roster.find(_.side == Side.Away).get
    val rules = MatchRules.standard
    val ball = QuaffleRules.free(Vec3(0, .5, 9), Vec3(0, -6, 0))
    val balls = MatchBalls(ball, SpecialBalls(Map.empty, None))
    val receiverState = demo.athletes(receiver.id).copy(
      flight = FlightState(Position(Vec3(0, 0, 9)), Velocity(Vec3.zero), Direction.east))

    def scene(position: Vec3, speed: Vec3 = Vec3.zero): MatchState =
      val opponent = demo.athletes(defender.id).copy(
        flight = FlightState(Position(position), Velocity(speed), Direction.west))
      demo.copy(athletes = Map(receiver.id -> receiverState, defender.id -> opponent),
        phase = MatchPhase.InPlay(balls), attackClock = None)

    def chance(state: MatchState, athlete: Athlete = receiver): Double =
      rules.catchSuccessProbability(state, athlete, ball.motion)

    val unopposed = scene(Vec3(0, 8, 9))
    val nearbyBehind = scene(Vec3(-1.6, 0, 9))
    val enteringLane = scene(Vec3(-1.6, 0, 9), Vec3(7, 0, 0))
    val blockingLane = scene(Vec3(0, 1.6, 9))
    assert(chance(unopposed) == 1 && chance(nearbyBehind) == 1)
    assert(chance(enteringLane) < 1 && chance(blockingLane) < 1)
    val unsteady = enteringLane.copy(athletes = enteringLane.athletes.updated(receiver.id,
      receiverState.copy(stability = Stability(30))))
    val weak = receiver.copy(ratings = receiver.ratings.copy(
      ballControl = receiver.ratings.ballControl.copy(catching = Rating(20))))
    assert(chance(unsteady) < chance(enteringLane) && chance(enteringLane, weak) < chance(enteringLane))

    val nextTick = enteringLane.clock.elapsed.value + 1
    val missSeed = Iterator.from(0).map(_.toLong)
      .find(seed => !rules.securesCatch(enteringLane, receiver, ball.motion, seed, nextTick)).get
    val attempt = receiver.id -> Control.Catch(CatchTarget.Quaffle)
    val inputs = MatchSimulation.Inputs()
    val miss = MatchSimulation.advance(enteringLane, balls, Vector(attempt, attempt), inputs, missSeed)
    assert(miss == MatchSimulation.advance(enteringLane, balls, Vector(attempt), inputs, missSeed))
    val disrupted = MatchEvent.QuaffleCatchDisrupted(receiver.id)
    val caught = MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Caught(receiver.id))
    assert(miss.events.contains(disrupted) && !miss.events.contains(caught))
    val frame = TimelineFrame(SimulationTick(nextTick), miss.state, miss.events)
    assert(frame.asJson.as[TimelineFrame] == Right(frame))
    assert(miss.state.athletes(receiver.id).pose.flatMap(_.ballAction).exists(_.kind == BallAction.Reaching))
    val tooSoon = MatchSimulation.advance(unopposed.copy(clock = miss.state.clock), balls, Vector(attempt),
      miss.inputs, missSeed)
    assert(!tooSoon.events.contains(caught) && !tooSoon.events.contains(disrupted))
    val ready = unopposed.copy(clock = unopposed.clock.copy(elapsed = PlayTick(nextTick + rules.catchRetryTicks - 1)))
    assert(MatchSimulation.advance(ready, balls, Vector(attempt), miss.inputs, missSeed).events.contains(caught))

  private def checkAttackPlanning(): Unit =
    val state = MatchSession.demo(cards, teamSize = 2).latest.state
    val home = state.setup.roster.filter(_.side == Side.Home)
    val away = state.setup.roster.filter(_.side == Side.Away)
    val shooter = home.head
    val receiver = home.last
    val allowed = Set(ControlKind.Shoot, ControlKind.Pass)

    def scene(positions: (Athlete, Vec3, Vec3)*): MatchState =
      val updated = positions.foldLeft(state.athletes) { case (athletes, (athlete, point, velocity)) =>
        val entry = athletes(athlete.id)
        athletes.updated(athlete.id, entry.copy(flight = entry.flight.copy(position = Position(point),
          velocity = Velocity(velocity))))
      }
      state.copy(athletes = updated)

    val moving = scene((shooter, Vec3(26, 1, 11), Vec3(12, 2, 0)),
      (receiver, Vec3(19, -8, 10), Vec3.zero), (away.head, Vec3(37, 13, 11), Vec3.zero),
      (away.last, Vec3(33, -15, 10), Vec3.zero))
    AttackPlanner.withQuaffle(moving, shooter, allowed) match
      case AttackPlanner.Decision.Shoot(aim) =>
        val physics = moving.setup.balls.quaffle
        val released = QuaffleRules.shoot(moving, shooter, aim, 42)
        val path = Iterator.iterate(QuaffleRules.predictStep(released, physics)) { (motion, _) =>
          QuaffleRules.predictStep(motion, physics)
        }
        assert(path.take(24).exists(_._2.exists(_._1 == Side.Home)))
      case other => throw AssertionError(s"Expected an open shot in motion, found $other")

    val outlet = scene((shooter, Vec3(10, 0, 10), Vec3.zero),
      (receiver, Vec3(20, 8, 10), Vec3.zero), (away.head, Vec3(12, -3, 10), Vec3.zero),
      (away.last, Vec3(33, -15, 10), Vec3.zero))
    assert(AttackPlanner.withQuaffle(outlet, shooter, allowed) == AttackPlanner.Decision.Pass(receiver.id))
    val blocked = outlet.athletes(away.head.id)
    val trappedLane = outlet.copy(athletes = outlet.athletes.updated(away.head.id,
      blocked.copy(flight = blocked.flight.copy(position = Position(Vec3(15, 4, 10.5))))))
    assert(AttackPlanner.withQuaffle(trappedLane, shooter, allowed).isInstanceOf[AttackPlanner.Decision.Carry])

  private def checkDefensePlanning(demo: MatchState): Unit =
    import DefensePlanner.Decision
    val attacker = demo.setup.roster.head
    val defender = demo.setup.roster.last
    def place(athlete: Athlete, point: Vec3, motion: Vec3 = Vec3.zero): AthleteState =
      demo.athletes(athlete.id).copy(flight = FlightState(Position(point), Velocity(motion), Direction.east))
    def scene(point: Vec3, guard: Vec3, velocity: Vec3 = Vec3.zero): MatchState =
      demo.copy(athletes = Map(attacker.id -> place(attacker, point), defender.id -> place(defender, guard, velocity)))
    def decision(state: MatchState): Decision = DefensePlanner.againstCarrier(state, defender, attacker.id)
    assert(decision(scene(Vec3(0, 0, 9), Vec3(39, 0, 10))).isInstanceOf[Decision.HoldGoal])
    assert(decision(scene(Vec3(22, 0, 9), Vec3(40, 0, 10))).isInstanceOf[Decision.CoverShotLane])
    assert(decision(scene(Vec3(20, 0, 9), Vec3(25, 0, 9))).isInstanceOf[Decision.Press])
    assert(decision(scene(Vec3(20, 0, 9), Vec3(22.5, 0, 9), Vec3(-7, 0, 0))).isInstanceOf[Decision.Challenge])
    assert(!decision(scene(Vec3(20, 0, 9), Vec3(17.5, 0, 9), Vec3(7, 0, 0))).isInstanceOf[Decision.Challenge])

    val late = scene(Vec3(20, 0, 9), Vec3(41, 9, 5))
    val fast = QuaffleRules.free(Vec3(42, 0, 12), Vec3(34, 0, 0)).motion
    assert(DefensePlanner.againstFreeBall(late, defender, fast, canUseCatch = true).isInstanceOf[Decision.HoldGoal])

    val slow = QuaffleRules.free(Vec3(23, 0, 12), Vec3(16, 0, 6.95))
    val staged = scene(Vec3(18, 0, 9), Vec3(40, 3, 13))
    val live = MatchBalls(slow, SpecialBalls(Map.empty, None))
    val initial = staged.copy(phase = MatchPhase.InPlay(live))
    val assignment = staged.controls.find(_.athlete == defender.id).get
    val plans = DefensePlanner.againstFreeBall(initial, defender, slow.motion, canUseCatch = true)
    assert(plans.isInstanceOf[Decision.Intercept])
    val steps = Iterator.iterate(MatchSimulation.Step(initial, Nil, MatchSimulation.Inputs())) { previous =>
      previous.state.phase match
        case MatchPhase.InPlay(balls) =>
          val controls = ComputerPilot.controls(previous.state, balls, assignment).map(defender.id -> _).toVector
          MatchSimulation.advance(previous.state, balls, controls, previous.inputs, 42)
        case _ => previous
    }
    val saved = steps.take(40).find(_.events.exists {
      case MatchEvent.QuaffleBlocked(by) => by == defender.id
      case MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Caught(receiver)) => receiver == defender.id
      case _ => false
    })
    assert(saved.exists(_.state.score == initial.score))

  private def checkCoordinatedDefense(): Unit =
    val initial = MatchSession.demo(cards, teamSize = 2, computerHome = true, computerOpponent = true).latest.state
    val first = initial.setup.roster.head.id
    val second = initial.setup.roster.find(athlete => athlete.side == Side.Home && athlete.id != first).get.id
    val approaching = FlightState(Position(Vec3(-3, 0, 10)), Velocity(Vec3(12, 0, 0)), Direction.east)
    val teammate = FlightState(Position(Vec3(3, 0, 10)), Velocity(Vec3(-12, 0, 0)), Direction.west)
    val headOn = FlightInput(AxisInput(1), AxisInput(0), AxisInput(0), false)
    assert(TeamSpacing.steer(first, approaching, List(second -> teammate), headOn).strafe.value != 0)

    for side <- Side.values do
      val defending = initial.setup.roster.filter(_.side == side)
      val carrier = initial.setup.roster.find(_.side != side).get
      val sign = if side == Side.Home then -1 else 1
      def placed(athlete: Athlete, point: Vec3): AthleteState =
        initial.athletes(athlete.id).copy(flight = FlightState(Position(point), Velocity(Vec3.zero),
          if sign > 0 then Direction.east else Direction.west))
      val positions = initial.athletes ++ Map(
        defending.head.id -> placed(defending.head, Vec3(39.7 * sign, 1.20, 10)),
        defending.last.id -> placed(defending.last, Vec3(39.71 * sign, 1.21, 10)),
        carrier.id -> placed(carrier, Vec3(1.9 * sign, 7.05, 10))
      )
      val balls = MatchBalls(QuaffleState.HeldBy(carrier.id), SpecialBalls(Map.empty, None))
      val start = initial.copy(phase = MatchPhase.InPlay(balls), athletes = positions)
      val lead = TeamDefense.marker(TeamDefense.plan(start, balls.quaffle, None)).get
      val partner = defending.find(_.id != lead).get
      assert(TeamDefense.marker(TeamDefense.plan(start, balls.quaffle, Some(partner.id))).contains(partner.id))
      val leadAthlete = defending.find(_.id == lead).get
      assert(DefensePlanner.againstCarrier(start, leadAthlete, carrier.id)
        .isInstanceOf[DefensePlanner.Decision.Press])

      val assignments = start.controls.filter(assignment => defending.exists(_.id == assignment.athlete))
      val end = (1 to 30).foldLeft(MatchSimulation.Step(start, Nil, MatchSimulation.Inputs())) { (step, _) =>
        val pressure = TeamDefense.plan(step.state, balls.quaffle, step.inputs.primaryDefender)
        val defender = TeamDefense.marker(pressure)
        val commands = assignments.flatMap(assignment =>
          ComputerPilot.controls(step.state, balls, assignment, defensiveTask = pressure.get(assignment.athlete))
            .map(assignment.athlete -> _)).toVector
        MatchSimulation.advance(step.state, balls, commands, step.inputs.copy(primaryDefender = defender), 42)
      }
      val marker = end.inputs.primaryDefender.get
      val covering = defending.find(_.id != marker).get.id
      val markerPosition = end.state.athletes(marker).flight.position.value
      val coverPosition = end.state.athletes(covering).flight.position.value
      assert((markerPosition - coverPosition).length > 2, s"$side did not separate: $markerPosition, $coverPosition")
      assert(math.abs(markerPosition.x) + 3 < math.abs(coverPosition.x),
        s"$side did not separate pressure from cover: $markerPosition, $coverPosition")

    val watch = MatchSession.demo(cards, teamSize = 2, computerHome = true, computerOpponent = true, shotSeed = 42)
    assert(watch.start().isRight)
    val sides = initial.setup.roster.map(athlete => athlete.id -> athlete.side).toMap
    var closeFrames = 0
    var longest = 0
    var passes = 0
    var completedPasses = 0
    var recipient = Option.empty[AthleteId]
    var movingShots = 0
    val frames = Iterator.continually(watch.advance()).take((watch.latest.state.clock.durationTicks * 2).toInt)
      .takeWhile(frame => !frame.state.phase.isInstanceOf[MatchPhase.Finished])
    frames.foreach { frame =>
      val passed = frame.events.exists(_.isInstanceOf[MatchEvent.QuafflePassed])
      if passed then passes += 1
      else movingShots += frame.events.count {
        case MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Released(id, _)) =>
          frame.state.athletes(id).flight.velocity.value.length > 2
        case _ => false
      }
      frame.events.foreach {
        case MatchEvent.QuafflePassed(_, receiver) => recipient = Some(receiver)
        case MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Caught(by)) =>
          if recipient.contains(by) then completedPasses += 1
          recipient = None
        case MatchEvent.QuaffleGoal(_) | MatchEvent.AttackExpired(_) | MatchEvent.FoulCalled(_) => recipient = None
        case _ => ()
      }
      val defending = frame.state.phase match
        case MatchPhase.InPlay(MatchBalls(QuaffleState.HeldBy(holder), _)) =>
          initial.setup.roster.filter(_.side != sides(holder))
        case _ => Nil
      val close = defending match
        case a :: b :: Nil =>
          val positions = frame.state.athletes
          (positions(a.id).flight.position.value - positions(b.id).flight.position.value).length < 2
        case _ => false
      closeFrames = if close then closeFrames + 1 else 0
      longest = math.max(longest, closeFrames)
    }
    assert(watch.latest.state.phase.isInstanceOf[MatchPhase.Finished])
    assert(longest <= MatchClock.ticksPerSecond, s"Defenders stayed within 2 meters for $longest ticks")
    val score = watch.latest.state.score
    assert(completedPasses > 0 && movingShots > 0 && score.home + score.away > 0)
    println(s"CPU vs CPU 2v2 (seed 42): $score, $completedPasses/$passes completed passes, $movingShots moving shots, " +
      s"longest defended overlap under 2 m was $longest ticks")

  private def checkTeamPressureAssignments(): Unit =
    val initial = MatchSession.demo(cards, teamSize = 2).latest.state
    val roster = Side.values.toList.flatMap { side =>
      val template = cards.find(_.side == side).get
      (1 to 7).map(index => template.copy(id = AthleteId(side.ordinal * 7 + index))).toList
    }
    val players = roster.map(athlete => Player(PlayerId(s"test-${athlete.id.value}"), athlete.side, PlayerKind.Computer))
    val assignments = players.zip(roster).map { (player, athlete) =>
      ControlAssignment(player.id, athlete.id, Set(ControlKind.Fly, ControlKind.Charge))
    }
    val setup = initial.setup.copy(roster = roster, players = players, assignments = assignments,
      roles = roster.map(_.id -> AthleteRole.Chaser).toMap)
    assert(MatchSession.create(MatchId("7v7-pressure-test"), setup, 100, 42).isRight)

    for side <- Side.values do
      val defending = roster.filter(_.side == side)
      val attacking = roster.filter(_.side != side)
      val carrier = attacking.head
      val goalward = if side == Side.Home then -1 else 1
      val attackingPoints = List(Vec3(0, 0, 11), Vec3(23 * goalward, -12, 8), Vec3(21 * goalward, 12, 14),
        Vec3(5 * goalward, 0, 20), Vec3(-6 * goalward, 3, 5), Vec3(-15 * goalward, -8, 10),
        Vec3(-18 * goalward, 5, 12))
      val defendingPoints = defending.indices.map { index =>
        Vec3(goalward * (5 + index * 4), (index % 3 - 1) * 6, 10)
      }
      val athletes = (attacking.zip(attackingPoints) ++ defending.zip(defendingPoints)).map { (athlete, position) =>
        val entry = MatchSimulation.initialAthlete(athlete.side)
        athlete.id -> entry.copy(flight = entry.flight.copy(position = Position(position)))
      }.toMap
      val ball = QuaffleState.HeldBy(carrier.id)
      val balls = MatchBalls(ball, SpecialBalls(Map.empty, None))
      val state = initial.copy(setup = setup, controls = assignments, athletes = athletes, phase = MatchPhase.InPlay(balls))
      val tasks = TeamDefense.plan(state, ball, None)
      assert(tasks.keySet == defending.map(_.id).toSet)
      assert(tasks.values.count(_ == TeamDefense.Task.MarkCarrier) == 1)
      val lanes = tasks.values.collect { case TeamDefense.Task.ClosePassingLane(receiver, point) => receiver -> point }.toList
      val zones = tasks.values.collect { case TeamDefense.Task.CoverZone(point) => point }.toList
      assert(lanes.map(_._1).toSet == attacking.slice(1, 3).map(_.id).toSet)
      assert(zones.size == 4)
      assert((lanes.map(_._2) ++ zones).combinations(2).forall(points =>
        (points.head.value - points.last.value).length >= 4))
      val commands = assignments.filter(assignment => tasks.contains(assignment.athlete)).flatMap { assignment =>
        ComputerPilot.controls(state, balls, assignment, defensiveTask = tasks.get(assignment.athlete))
          .map(assignment.athlete -> _)
      }
      assert(commands.collect { case (id, _: Control.Fly) => id }.toSet == tasks.keySet)
      val next = MatchSimulation.advance(state, balls, commands.toVector, MatchSimulation.Inputs(), 42).state
      tasks.foreach { (id, task) =>
        val target = task match
          case TeamDefense.Task.MarkCarrier => athletes(carrier.id).flight.position
          case TeamDefense.Task.ClosePassingLane(_, point) => point
          case TeamDefense.Task.CoverZone(point) => point
        val flight = next.athletes(id).flight
        assert(flight.velocity.value.dot(target.value - athletes(id).flight.position.value) > 0)
      }
      assert(TeamDefense.plan(state, QuaffleRules.free(Vec3.zero), TeamDefense.marker(tasks)).isEmpty)
      val afterTurnover = TeamDefense.plan(state, QuaffleState.HeldBy(defending.head.id), TeamDefense.marker(tasks))
      assert(afterTurnover.keySet == attacking.map(_.id).toSet)
      val cover = TeamTactics.default.copy(defense = DefensiveApproach.Cover)
      val coverState = state.copy(setup = setup.copy(tactics = Some(MatchTactics(cover, cover))))
      val coverTasks = TeamDefense.plan(coverState, ball, None)
      assert(coverTasks.keySet == tasks.keySet)
      assert(coverTasks.values.count(_.isInstanceOf[TeamDefense.Task.CoverZone]) == 6)

  private def checkTeamTactics(): Unit =
    val setup = MatchSession.demo(cards, teamSize = 2).latest.state.setup
    val before = setup.asJson.mapObject(_.remove("tactics")).as[MatchSetup].toOption.get
    assert(Side.values.forall(side => before.tacticsFor(side) == TeamTactics.default))
    assert(decode[TeamTactics]("""{"attackSupport":"Unknown","defense":"Cover"}""").isLeft)
    val session = MatchSession.demo(cards, teamSize = 2, shotSeed = 42)
    val initial = session.latest.state
    val home = initial.controls.find(_.player == PlayerId("home")).get.athlete
    val away = initial.controls.find(_.player == PlayerId("away")).get.athlete
    val teammate = initial.controls.find(assignment => assignment.athlete != home &&
      setup.roster.exists(athlete => athlete.id == assignment.athlete && athlete.side == Side.Home)).get

    def after(tactics: TeamTactics, holder: AthleteId, at: Vec3, ticks: Int): Vec3 =
      val balls = MatchBalls(QuaffleState.HeldBy(holder), SpecialBalls(Map.empty, None))
      val arranged = initial.athletes
        .updated(home, initial.athletes(home).copy(flight =
          initial.athletes(home).flight.copy(position = Position(Vec3(-33, 12, 8)))))
        .updated(holder, initial.athletes(holder).copy(flight =
          initial.athletes(holder).flight.copy(position = Position(at))))
        .updated(teammate.athlete, initial.athletes(teammate.athlete).copy(flight =
          initial.athletes(teammate.athlete).flight.copy(position = Position(Vec3(-25, 0, 10)))))
      val configured = setup.copy(tactics = Some(MatchTactics(tactics, TeamTactics.default)))
      val start = initial.copy(setup = configured, phase = MatchPhase.InPlay(balls), athletes = arranged)
      val end = (1 to ticks).foldLeft(MatchSimulation.Step(start, Nil, MatchSimulation.Inputs())) { (step, _) =>
        val task = TeamDefense.plan(step.state, balls.quaffle, step.inputs.primaryDefender).get(teammate.athlete)
        val controls = ComputerPilot.controls(step.state, balls, teammate, defensiveTask = task).map(teammate.athlete -> _).toVector
        MatchSimulation.advance(step.state, balls, controls, step.inputs, 42)
      }
      end.state.athletes(teammate.athlete).flight.position.value

    val nearby = after(TeamTactics.default.copy(attackSupport = AttackSupport.Nearby), home, Vec3(0, 0, 10), 45)
    val wide = after(TeamTactics.default, home, Vec3(0, 0, 10), 45)
    assert(wide.y > nearby.y + 2)
    val pressure = after(TeamTactics.default, away, Vec3(-20, 0, 10), 12)
    val cover = after(TeamTactics.default.copy(defense = DefensiveApproach.Cover), away, Vec3(-20, 0, 10), 12)
    assert(pressure.x > -25 && cover.x < -25)

  private def checkHistory(): Unit =
    val directory = Files.createTempDirectory("quidditch-history-")
    val path = directory.resolve("matches.sqlite3")
    val archived = MatchId("archived")
    val current = MatchId("current")
    try
      def runMatch(id: MatchId): TimelineFrame =
        Database.connect(path).use { connection =>
          for
            _ <- TimelineTableInitializer.initialize(connection)
            _ <- AthleteCardTableInitializer.initialize(connection)
            loaded <- AthleteCardTable.list(connection)
          yield
            val session = MatchSession.demo(loaded, id = id,
              recordFrame = frame => TimelineTable.insert(connection, frame).unsafeRunSync())
            val tactics = TeamTactics(AttackSupport.Nearby, DefensiveApproach.Cover)
            val setup = MatchSession.demoSetup(loaded, false, false, 1, None, tactics).toOption.get
            assert(session.start(Some(setup)).isRight)
            assert(session.submit(PlayerId("home"), Control.Charge).isRight)
            session.advance()
        }.unsafeRunSync()
      val oldFrame = runMatch(archived)
      assert(oldFrame.state.setup.tacticsFor(Side.Home) == TeamTactics(AttackSupport.Nearby, DefensiveApproach.Cover))
      assert(oldFrame.state.setup.tacticsFor(Side.Away) == TeamTactics.default)
      Database.connect(path).use { connection =>
        IO.blocking {
          Using.resource(connection.createStatement()) { statement =>
            val changed = statement.executeUpdate(
              "UPDATE athlete_cards SET card = json_set(card, '$.ratings.flight.speed', 92) WHERE athlete_id = 1"
            )
            assert(changed == 1)
          }
        }
      }.unsafeRunSync()
      val newFrame = runMatch(current)
      assert(oldFrame.state.setup.roster.head.ratings.flight.speed == Rating(85))
      assert(newFrame.state.setup.roster.head.ratings.flight.speed == Rating(92))
      assert(oldFrame.events.contains(MatchEvent.ContactAttempted(AthleteId(1), ContactKind.Charge)))
      assert(oldFrame.tick == newFrame.tick && oldFrame.state.id != newFrame.state.id)
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/", MatchRoutes(MatchSession.demo(cards, id = current), Some(path)))
      server.start()
      val client = HttpClient.newHttpClient()
      try
        def get(historyPath: String) =
          val uri = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}$historyPath")
          client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
        val matches = get("/matches/history")
        assert(matches.statusCode() == 200 && decode[List[MatchId]](matches.body()) == Right(List(current, archived)))
        val old = get(s"/matches/history/${archived.value}/timeline/${oldFrame.tick.value}")
        assert(old.statusCode() == 200 && decode[TimelineFrame](old.body()) == Right(oldFrame))
        assert(get(s"/matches/history/${archived.value}/timeline/5000").statusCode() == 404)
      finally
        client.close()
        server.stop(0)
    finally
      List(path, Path.of(s"$path-wal"), Path.of(s"$path-shm")).foreach(Files.deleteIfExists)
      Files.delete(directory)

  private def checkHttp(): Unit =
    val session = MatchSession.demo(cards)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", MatchRoutes(session))
    server.start()
    val client = HttpClient.newHttpClient()
    try
      def request(path: String, method: String = "GET", body: String = "") =
        val uri = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}$path")
        val query = HttpRequest.newBuilder(uri)
          .method(method, HttpRequest.BodyPublishers.ofString(body)).build()
        client.send(query, HttpResponse.BodyHandlers.ofString())
      assert(request("/health").statusCode() == 200)
      assert(request("/matches/demo/start", "POST").statusCode() == 200)
      val input = """{"player":"home","control":{"Fly":{"input":{"forward":1,"strafe":0,"climb":0,"sprint":false}}}}"""
      val accepted = request("/matches/demo/controls", "POST", input)
      assert(accepted.statusCode() == 202)
      val tick = parse(accepted.body()).flatMap(_.hcursor.get[SimulationTick]("scheduledTick")).toOption.get
      assert(request(s"/matches/demo/timeline/${tick.value}").statusCode() == 404)
      session.advance()
      val recorded = request(s"/matches/demo/timeline/${tick.value}")
      val frame = decode[TimelineFrame](recorded.body()).toOption.get
      assert(frame.tick == tick && frame.state.athletes(AthleteId(1)).flight.velocity.value.x > 0)
      assert(request("/matches/demo/controls", "POST", input.replace("\"forward\":1", "\"forward\":2")).statusCode() == 400)
    finally
      client.close()
      server.stop(0)
