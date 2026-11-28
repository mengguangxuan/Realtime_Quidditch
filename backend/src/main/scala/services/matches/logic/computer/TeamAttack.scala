package services.matches.logic

import services.matches.objects.*
import FlightRules.clamp

private[matches] def looseBallPursuer(state: MatchState, team: List[Athlete], point: Vec3,
    passTarget: Option[AthleteId]): AthleteId =
  val outfield = team.filter(athlete => state.setup.roles(athlete.id) != AthleteRole.Keeper)
  val candidates = if outfield.nonEmpty then outfield else team
  passTarget.filter(id => team.exists(_.id == id)).getOrElse {
    candidates.minBy(athlete => (state.athletes(athlete.id).flight.position.value - point).length).id
  }

private[matches] def teamSupport(state: MatchState, ball: QuaffleState, passTarget: Option[AthleteId],
    computers: Set[AthleteId], previousSlots: Map[AthleteId, Int])
    : (Map[AthleteId, Position], Map[AthleteId, Int]) =
  val teams = state.setup.roster.groupBy(_.side)
  val anchors = ball match
    case QuaffleState.HeldBy(holder) =>
      val side = state.setup.roster.find(_.id == holder).get.side
      val flight = state.athletes(holder).flight
      List((side, holder, flight.position.value + flight.velocity.value * .25))
    case QuaffleState.Free(motion) =>
      Side.values.toList.map { side =>
        val pursuer = looseBallPursuer(state, teams(side), motion.position.value, passTarget)
        val sign = if side == Side.Home then 1 else -1
        (side, pursuer, motion.position.value + motion.velocity.value * .25 - Vec3(sign * 8, 0, 0))
      }
  val assignments = anchors.flatMap { (side, lead, anchor) =>
    val team = teams(side)
    if team.size <= 2 then Nil
    else
      val candidates = team.filter(athlete =>
        athlete.id != lead && computers.contains(athlete.id) && state.setup.roles(athlete.id) != AthleteRole.Keeper)
      val sign = if side == Side.Home then 1 else -1
      val width = if state.setup.tacticsFor(side).attackSupport == AttackSupport.Wide then 11 else 5
      val center = Vec3(clamp(anchor.x * sign, -30, 26) * sign, clamp(anchor.y * .45, -7, 7), clamp(anchor.z, 5, 19))
      // Short outlet, opposite wing, forward runner and three staggered options behind the ball.
      val offsets = Vector(Vec3(3, width, 0), Vec3(8, -width, 2), Vec3(13, 0, 3),
        Vec3(-6, -width, 0), Vec3(-9, width, 1), Vec3(-15, 0, 0))
      val (_, assigned) = offsets.take(candidates.size).zipWithIndex.foldLeft((candidates, List.empty[(AthleteId, Position, Int)])) {
        case ((available, assigned), (offset, index)) =>
          val point = FlightRules.contain(center + Vec3(offset.x * sign, offset.y, offset.z), 4)
          val athlete = available.minBy { candidate =>
            val flight = state.athletes(candidate.id).flight
            val distance = (flight.position.value + flight.velocity.value * .25 - point).length
            val switchCost = if previousSlots.get(candidate.id).exists(_ != index) then 3.0 else 0.0
            (distance + switchCost, candidate.id.value)
          }
          (available.filterNot(_.id == athlete.id), (athlete.id, Position(point), index) :: assigned)
      }
      assigned
  }
  (assignments.map((id, target, _) => id -> target).toMap, assignments.map((id, _, slot) => id -> slot).toMap)
