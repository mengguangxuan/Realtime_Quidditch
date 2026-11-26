package services.matches.logic

import services.matches.objects.*

private[matches] object TeamDefense:
  private val taskSpacing = 4.0

  enum Task:
    case MarkCarrier
    case ClosePassingLane(receiver: AthleteId, target: Position)
    case CoverZone(target: Position)

  def plan(state: MatchState, quaffle: QuaffleState, previous: Option[AthleteId]): Map[AthleteId, Task] =
    quaffle match
      case QuaffleState.Free(_) => Map.empty
      case QuaffleState.HeldBy(carrier) =>
        val side = MatchRules.opponent(state.setup.roster.find(_.id == carrier).get.side)
        val team = state.setup.roster.filter(_.side == side)
        val defending = team.filter(athlete => state.setup.roles(athlete.id) != AthleteRole.Keeper)
        if defending.isEmpty then return Map.empty
        val ball = state.athletes(carrier).flight.position.value
        val distances = defending.map(athlete =>
          athlete -> (state.athletes(athlete.id).flight.position.value - ball).length)
        val (closest, distance) = distances.minBy { case (athlete, gap) => (gap, athlete.id.value) }
        val marker = distances.find { case (athlete, gap) =>
          previous.contains(athlete.id) && gap <= distance + 3
        }.fold(closest)(_._1)
        val goalClearance = if team.size > defending.size then taskSpacing else 0.0
        distribute(state, carrier, ball, marker, defending.filterNot(_.id == marker.id), goalClearance)

  def marker(tasks: Map[AthleteId, Task]): Option[AthleteId] =
    tasks.collectFirst { case (id, Task.MarkCarrier) => id }

  private def distribute(state: MatchState, carrier: AthleteId, ball: Vec3, marker: Athlete,
      support: List[Athlete], goalClearance: Double): Map[AthleteId, Task] =
    val goalX = if marker.side == Side.Home then -Pitch.alpine.goalPlaneX else Pitch.alpine.goalPlaneX
    val wing = if ball.y >= 0 then -6 else 6
    val lanes = state.setup.tacticsFor(marker.side).defense match
      case DefensiveApproach.Pressure if support.size >= 2 =>
        val firstLaneDepth = coverDepth((support.size - 1) / 2) + taskSpacing + goalClearance
        passingLanes(state, carrier, ball, marker.side, goalX, firstLaneDepth, math.min(2, support.size / 2))
      case DefensiveApproach.Pressure | DefensiveApproach.Cover => Vector.empty
    val zones = Vector.tabulate(support.size - lanes.size) { index =>
      val row = index / 2
      val lane = if index % 2 == 0 then wing else -wing
      val depth = coverDepth(row) + goalClearance
      val target = FlightRules.contain(Vec3(goalX - math.signum(goalX) * depth, lane, 10 + row * 3), 1.5)
      Task.CoverZone(Position(target)) -> target
    }
    val (_, assignments) = (lanes ++ zones).foldLeft((support, Map[AthleteId, Task](marker.id -> Task.MarkCarrier))) {
      case ((available, tasks), (task, target)) =>
        val closest = available.minBy { athlete =>
          ((state.athletes(athlete.id).flight.position.value - target).length, athlete.id.value)
        }
        (available.filterNot(_.id == closest.id), tasks.updated(closest.id, task))
    }
    assignments

  private def passingLanes(state: MatchState, carrier: AthleteId, ball: Vec3, defending: Side,
      goalX: Double, firstLaneDepth: Double, limit: Int): Vector[(Task, Vec3)] =
    val receivers = state.setup.roster.filter(athlete => athlete.side != defending && athlete.id != carrier)
      .map { athlete =>
        val flight = state.athletes(athlete.id).flight
        athlete.id -> FlightRules.contain(flight.position.value + flight.velocity.value * .35, 1.5)
      }
      .sortBy { case (id, point) => ((point - Vec3(goalX, 0, 10)).length, id.value) }

    receivers.foldLeft(Vector.empty[(Task, Vec3)]) { case (selected, (receiver, point)) =>
      val target = ball + (point - ball) * .55
      val distinct = selected.forall { case (_, other) => (target - other).length >= taskSpacing }
      val depth = (goalX - target.x) * math.signum(goalX)
      if selected.size < limit && distinct && (target - ball).length >= taskSpacing && depth >= firstLaneDepth then
        selected :+ (Task.ClosePassingLane(receiver, Position(target)) -> target)
      else selected
    }

  private def coverDepth(row: Int): Double = 3 + row * 4.5
