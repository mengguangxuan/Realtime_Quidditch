package services.matches.logic

import com.sun.net.httpserver.HttpServer
import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import services.matches.api.ApplyControlMessage
import services.matches.objects.*
import services.matches.routes.MatchRoutes

object MatchChecks:
  def main(args: Array[String]): Unit =
    val matchSession = MatchSession.demo(shotSeed = 42)
    val home = PlayerId("home")
    val homeAthlete = AthleteId(1)
    assert(matchSession.latest.tick == SimulationTick(0))
    assert(matchSession.frame(SimulationTick(-1)).isEmpty)
    assert(matchSession.start().isRight)
    assert(matchSession.submit(PlayerId("guest"), Control.Charge) == Left(ActionRejection.PlayerNotInMatch))
    assert(matchSession.switchAthlete(home, AthleteId(2)) == Left(ActionRejection.AthleteNotOnPlayersSide))

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
    val catchCommand = ApplyControlMessage(home, Control.Catch(CatchTarget.Quaffle))
    assert(decode[ApplyControlMessage](catchCommand.asJson.noSpaces) == Right(catchCommand))
    assert(catchCommand.plan(matchSession).isRight)
    val caught = matchSession.advance()
    assert(caught.events.contains(MatchEvent.QuafflePossessionChanged(QuafflePossessionOutcome.Caught(homeAthlete))))
    assert(decode[TimelineFrame](caught.asJson.noSpaces) == Right(caught))

    steer(home, homeAthlete, Vec3(36, 7.1, 10.0))
    val position = matchSession.latest.state.athletes(homeAthlete).flight.position.value
    val direction = Direction.from(Vec3(44, 7.1, 10.45) - position).toOption.get
    assert(matchSession.submit(home, Control.Shoot(ShotAim(direction, ShotPower(1), None))).isRight)
    val released = matchSession.advance()
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
    val (_, dropped, _) = FlightRules.contact(contactState, live, challenger, carrier, ContactKind.Charge)
    assert(dropped.quaffle.isInstanceOf[QuaffleState.Free])
    val braced = contactState.copy(athletes = contactState.athletes.updated(carrier.id,
      holder.copy(movement = MovementMode.Bracing)))
    assert(FlightRules.contact(braced, live, challenger, carrier, ContactKind.Charge)._2.quaffle == live.quaffle)
    val (fouled, _, events) = FlightRules.contact(contactState, live, challenger, carrier, ContactKind.Grab)
    assert(fouled.fouls(challenger.id) == 1)
    assert(fouled.phase.isInstanceOf[MatchPhase.RestartPending])
    assert(events.exists {
      case MatchEvent.FoulCalled(Foul(_, _, FoulKind.Grabbing, _, _)) => true
      case _ => false
    })
    checkHttp()
    checkShotsAndBlocks(demo)
    val solo = MatchSession.demo(computerOpponent = true, shotSeed = 42)
    assert(solo.start().isRight)
    assert(solo.submit(PlayerId("away"), Control.Charge) == Left(ActionRejection.PlayerIsComputer))
    checkControlSwitch(solo.latest.state.setup)
    assert(Iterator.fill(900)(()).map(_ => solo.advance()).exists(_.state.score.away == 10))
    println("Verified controls, catch, release, goal, restart, contact, foul, timeline and HTTP routes")

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
    val good = ShotAccuracy.probability(home, settled, aim)
    assert(ShotAccuracy.probability(home,
      settled.copy(flight = settled.flight.copy(position = Position(Vec3(5, 0, 12)))), aim) < good)
    assert(ShotAccuracy.probability(home,
      settled.copy(flight = settled.flight.copy(facing = Direction.west)), aim) < good)
    assert(ShotAccuracy.probability(home,
      settled.copy(flight = settled.flight.copy(velocity = Velocity(Vec3(0, 20, 0)))), aim) < good)
    val poorFinisher = home.copy(ratings = home.ratings.copy(
      throwing = home.ratings.throwing.copy(finishing = Rating(25))))
    assert(ShotAccuracy.probability(poorFinisher, settled, aim) < good)
    val seed = 42L
    assert(ShotAccuracy.direction(home, settled, aim, seed) == aim.direction)
    assert(ShotAccuracy.direction(home, settled.copy(flight = settled.flight.copy(facing = Direction.west)),
      aim, seed) != aim.direction)

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

  private def checkHttp(): Unit =
    val session = MatchSession.demo()
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
