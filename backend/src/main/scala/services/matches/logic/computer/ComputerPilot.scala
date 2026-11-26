package services.matches.logic

import services.matches.objects.*

private[matches] object ComputerPilot:
  def penaltyShot(athlete: AthleteState, side: Side): Option[Control] =
    val sign = if side == Side.Home then 1 else -1
    val target = Vec3(Pitch.alpine.goalPlaneX * sign, 7.1, 10.6)
    Direction.from(target - athlete.flight.position.value).toOption
      .map(direction => Control.Shoot(ShotAim(direction, ShotPower(1), None)))

  def controls(state: MatchState, balls: MatchBalls, assignment: ControlAssignment,
      passTarget: Option[AthleteId] = None, primaryDefender: Option[AthleteId] = None): List[Control] =
    val athlete = state.setup.roster.find(_.id == assignment.athlete).get
    val flight = state.athletes(athlete.id).flight
    val position = flight.position.value
    val velocity = flight.velocity.value
    val sign = if athlete.side == Side.Home then 1 else -1
    val tactics = state.setup.tacticsFor(athlete.side)

    val team = state.setup.roster.filter(_.side == athlete.side)
    val teammates = team.filterNot(_.id == athlete.id).map(mate => mate.id -> state.athletes(mate.id).flight)
    def move(target: Vec3, sprint: Boolean = false): Control =
      val input = DefensePlanner.flightTo(flight, target, sprint)
      Control.Fly(TeamSpacing.steer(athlete.id, flight, teammates, input))

    def nearest(point: Vec3): AthleteId =
      team.minBy(mate => (state.athletes(mate.id).flight.position.value - point).length).id

    def support(point: Vec3): List[Control] =
      val inward = if point.y > 0 then -1 else 1
      val target = tactics.attackSupport match
        case AttackSupport.Nearby => Vec3(point.x + sign * 3, point.y + inward * 4, math.max(4, point.z))
        case AttackSupport.Wide => Vec3(point.x + sign * 6, inward * 9, math.max(4, point.z))
      List(move(FlightRules.contain(target, 1.5)))

    import DefensePlanner.{Decision, InterceptMethod}
    def defend(decision: Decision): List[Control] = decision match
      case Decision.HoldGoal(point) => List(move(point.value))
      case Decision.CoverShotLane(_, _, point) => List(move(point.value))
      case Decision.Press(_, cutoff) => List(move(cutoff.value, sprint = true))
      case Decision.Challenge(_, approach) => List(move(approach.value), Control.Charge)
      case Decision.PursueLoose(point) => List(move(point.value))
      case Decision.Intercept(sample, method) =>
        val pilot = move(DefensePlanner.interceptPosition(sample))
        val catching = method == InterceptMethod.Catch && sample.at == state.clock.elapsed
        if catching then List(pilot, Control.Catch(CatchTarget.Quaffle)) else List(pilot)

    val decisions = balls.quaffle match
      case QuaffleState.Free(motion) =>
        val point = motion.position.value
        val pursuer = passTarget.filter(id => team.exists(_.id == id)).getOrElse(nearest(point))
        if pursuer == athlete.id then
          defend(DefensePlanner.againstFreeBall(state, athlete, motion,
            assignment.allowedControls.contains(ControlKind.Catch)))
        else
          val lead = state.athletes(pursuer).flight.position.value
          val lane = if point.y > 0 then -9 else 9
          List(move(FlightRules.contain(Vec3(lead.x - sign * 5, lane, math.max(4, lead.z)), 1.5)))
      case QuaffleState.HeldBy(id) if id == athlete.id =>
        val shootingSpot = Vec3(36 * sign, 7.1, 10)
        val ring = Vec3(44 * sign, 7.1, 10.45)
        val aim = Direction.from(ring - position).toOption
        val shot = aim.filter(_ => (position - shootingSpot).length < .75 && velocity.length < 2)
          .map(direction => Control.Shoot(ShotAim(direction, ShotPower(1), None)))
        move(shootingSpot) :: shot.toList
      case QuaffleState.HeldBy(id) =>
        val carrier = state.athletes(id).flight.position.value
        if team.exists(_.id == id) then support(carrier)
        else if primaryDefender.getOrElse(nearest(carrier)) == athlete.id then
          defend(DefensePlanner.againstCarrier(state, athlete, id, tactics.defense, hasCover = teammates.nonEmpty))
        else List(move(DefensePlanner.coverBehind(athlete, carrier)))
    decisions.filter(control => assignment.allowedControls.contains(control.kind))
