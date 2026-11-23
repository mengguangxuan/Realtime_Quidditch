package services.matches.logic

import services.matches.objects.*

private[matches] object ComputerPilot:
  def controls(state: MatchState, balls: MatchBalls, assignment: ControlAssignment): List[Control] =
    val athlete = state.setup.roster.find(_.id == assignment.athlete).get
    val flight = state.athletes(athlete.id).flight
    val position = flight.position.value
    val velocity = flight.velocity.value
    val sign = if athlete.side == Side.Home then 1 else -1

    def move(target: Vec3): Control =
      val adjustment = (target - position) * .24 - velocity * .075
      def axis(value: Double): AxisInput = AxisInput(FlightRules.clamp(value, -1, 1))
      Control.Fly(FlightInput(axis(adjustment.x), axis(adjustment.y), axis(adjustment.z), false))

    val decisions = balls.quaffle match
      case QuaffleState.Free(motion) =>
        val point = motion.position.value
        val intercept = Vec3(point.x, point.y, math.max(1.5, point.z))
        List(move(intercept), Control.Catch(CatchTarget.Quaffle))
      case QuaffleState.HeldBy(id) if id == athlete.id =>
        val shootingSpot = Vec3(36 * sign, 7.1, 10)
        val ring = Vec3(44 * sign, 7.1, 10.45)
        val aim = Direction.from(ring - position).toOption
        val shot = aim.filter(_ => (position - shootingSpot).length < .75 && velocity.length < 2)
          .map(direction => Control.Shoot(ShotAim(direction, ShotPower(1), None)))
        move(shootingSpot) :: shot.toList
      case QuaffleState.HeldBy(id) =>
        val carrier = state.athletes(id).flight.position.value
        val guard = Vec3(-33 * sign, FlightRules.clamp(carrier.y, -10, 10), FlightRules.clamp(carrier.z, 6, 12))
        val target = if (carrier - position).length < 7 then carrier else guard
        val charge = Option.when((position - carrier).length < 2.8)(Control.Charge)
        move(target) :: charge.toList
    decisions.filter(control => assignment.allowedControls.contains(control.kind))
