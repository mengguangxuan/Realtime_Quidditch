package services.matches.logic

import services.matches.objects.*

private[matches] object AthleteMotion:
  def tick(state: MatchState): MatchState =
    state.copy(athletes = state.athletes.map { (id, athlete) =>
      val action = athlete.pose.flatMap(_.ballAction).filter(_.ticksRemaining > 1)
        .map(action => action.copy(ticksRemaining = action.ticksRemaining - 1))
      id -> withPose(athlete, athlete.pose.exists(_.diving), action)
    })

  def flight(athlete: AthleteState, input: Option[FlightInput]): AthleteState =
    val diving = input.exists(_.climb.value < 0) && athlete.flight.velocity.value.z <= -2
    withPose(athlete, diving, athlete.pose.flatMap(_.ballAction))

  def ballAction(state: MatchState, id: AthleteId, kind: BallAction): MatchState =
    val athlete = state.athletes(id)
    val active = athlete.pose.flatMap(_.ballAction)
    if kind == BallAction.Reaching && active.nonEmpty then state
    else
      val duration = kind match
        case BallAction.Reaching => 4
        case BallAction.Securing | BallAction.Passing => 5
        case BallAction.Shooting => 6
      val updated = withPose(athlete, athlete.pose.exists(_.diving), Some(TimedBallAction(kind, duration)))
      state.copy(athletes = state.athletes.updated(id, updated))

  private def withPose(athlete: AthleteState, diving: Boolean, action: Option[TimedBallAction]): AthleteState =
    athlete.copy(pose = Option.when(diving || action.nonEmpty)(AthletePose(diving, action)))
