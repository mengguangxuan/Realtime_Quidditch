package services.matches.logic

import services.matches.objects.*

private[matches] object ComputerPilot:
  def controls(state: MatchState, balls: MatchBalls, assignment: ControlAssignment): List[Control] =
    val athlete = state.setup.roster.find(_.id == assignment.athlete).get
    val flight = state.athletes(athlete.id).flight
    val position = flight.position.value
    val velocity = flight.velocity.value
    val sign = if athlete.side == Side.Home then 1 else -1

    def move(target: Vec3, sprint: Boolean = false): Control =
      Control.Fly(DefensePlanner.flightTo(flight, target, sprint))

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
        defend(DefensePlanner.againstFreeBall(state, athlete, motion,
          assignment.allowedControls.contains(ControlKind.Catch)))
      case QuaffleState.HeldBy(id) if id == athlete.id =>
        val shootingSpot = Vec3(36 * sign, 7.1, 10)
        val ring = Vec3(44 * sign, 7.1, 10.45)
        val aim = Direction.from(ring - position).toOption
        val shot = aim.filter(_ => (position - shootingSpot).length < .75 && velocity.length < 2)
          .map(direction => Control.Shoot(ShotAim(direction, ShotPower(1), None)))
        move(shootingSpot) :: shot.toList
      case QuaffleState.HeldBy(id) =>
        defend(DefensePlanner.againstCarrier(state, athlete, id))
    decisions.filter(control => assignment.allowedControls.contains(control.kind))
