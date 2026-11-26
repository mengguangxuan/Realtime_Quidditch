package services.matches.logic

import scala.util.Random
import services.matches.objects.*

/** Competition rules for the current Quaffle-only format. */
final case class MatchRules(attackSeconds: Int = 18):
  require(attackSeconds > 0, "Attack time must be positive")

  private[logic] val attackTicks = attackSeconds.toLong * MatchClock.ticksPerSecond
  private[logic] val restartTicks = MatchClock.ticksPerSecond
  private[logic] val penaltyAimTicks = 8L * MatchClock.ticksPerSecond
  private[logic] val shotFlightTicks = 3L * MatchClock.ticksPerSecond
  private[logic] val catchRetryTicks = 5

  private[logic] def attackFor(side: Side): MatchClock.Attack = MatchClock.Attack(side, attackTicks)

  private[logic] def catchSuccessProbability(state: MatchState, receiver: Athlete, ball: BallMotion): Double =
    val athlete = state.athletes(receiver.id)
    val flight = athlete.flight
    val position = flight.position.value
    val incoming = ball.velocity.value - flight.velocity.value
    val ballSide = Direction.from(ball.position.value - position)
      .orElse(Direction.from(incoming * -1)).toOption
    val pressure = state.setup.roster.iterator.filter(_.side != receiver.side).map { opponent =>
      val defender = state.athletes(opponent.id).flight
      val offset = defender.position.value - position
      val distance = offset.length
      if distance >= 3.4 then 0.0
      else
        val towardDefender = Direction.from(offset).map(_.value).getOrElse(flight.facing.value)
        val closing = -(defender.velocity.value - flight.velocity.value).dot(towardDefender)
        val approach = FlightRules.clamp(closing / 7, 0, 1)
        val inReceivingLane = incoming.length >= 3 && ballSide.exists(_.value.dot(towardDefender) >= .55)
        val proximity = FlightRules.clamp((3.4 - distance) / 2.4, 0, 1)
        proximity * math.max(approach, if inReceivingLane then .8 else 0)
    }.maxOption.getOrElse(0.0)
    val control = (receiver.ratings.ballControl.catching.value * .6 + athlete.stability.value * .4) / 100
    1 - pressure * (.72 - .37 * control)

  private[logic] def securesCatch(state: MatchState, receiver: Athlete, ball: BallMotion,
      seed: Long, tick: Long): Boolean =
    val probability = catchSuccessProbability(state, receiver, ball)
    probability == 1 || new Random(seed ^ (tick * 0x9e3779b97f4a7c15L) ^
      (receiver.id.value.toLong * 0xbf58476d1ce4e5b9L)).nextDouble() < probability

  private[logic] def foulRestart(side: Side, foul: Foul, count: Int): Restart =
    if foul.deniedClearChance || count >= 2 then Restart.PenaltyThrow(side, foul)
    else Restart.FreePossession(side, foul.position, foul)

  private[logic] def endRestart(side: Side, after: MatchEvent): Restart =
    val homeward = if side == Side.Home then -1 else 1
    Restart.TeamPossession(side, Position(Vec3(36 * homeward, 0, 9)), after)

  private[logic] def penaltySpot(side: Side): Vec3 =
    val direction = if side == Side.Home then 1 else -1
    Vec3(33 * direction, 0, 12)

  private[logic] def penaltyDefenderSpot(side: Side): Vec3 =
    val direction = if side == Side.Home then 1 else -1
    Vec3(42 * direction, 0, 12)

  private[logic] def canTakePenalty(penalty: MatchPhase.Penalty, athlete: AthleteId): Boolean =
    penalty.shooter == athlete && penalty.balls.quaffle == QuaffleState.HeldBy(athlete)

  private[logic] def penaltyShotResult(penalty: MatchPhase.Penalty, flight: QuaffleRules.BallStep,
      ticksLeft: Long): Either[BallMotion, MatchRules.PenaltyResult] =
    import QuaffleRules.BallStep
    flight match
      case BallStep.Scored(side) if side == penalty.side => Right(MatchEvent.PenaltyScored(penalty.shooter))
      case BallStep.Blocked(by, _) => Right(MatchEvent.PenaltyBlocked(penalty.shooter, by))
      case BallStep.Moved(next, false) if ticksLeft > 0 => Left(next)
      case _ => Right(MatchEvent.PenaltyMissed(penalty.shooter))

  private[logic] def finishPenalty(state: MatchState, penalty: MatchPhase.Penalty,
      outcome: MatchRules.PenaltyResult): (MatchState, List[MatchEvent]) =
    val side = penalty.side
    val ended = state.clock.elapsed.value >= state.clock.durationTicks
    val phase = if ended then MatchPhase.Finished(FinishReason.TimeExpired)
      else MatchPhase.RestartPending(endRestart(MatchRules.opponent(side), outcome), penalty.balls.special)
    val (score, consequence) = outcome match
      case MatchEvent.PenaltyScored(_) => (MatchRules.addGoal(state.score, side), List(MatchEvent.QuaffleGoal(side)))
      case MatchEvent.PenaltyBlocked(_, by) => (state.score, List(MatchEvent.QuaffleBlocked(by)))
      case _ => (state.score, Nil)
    val finish = Option.when(ended)(MatchEvent.MatchEnded(FinishReason.TimeExpired))
    (state.copy(phase = phase, score = score), (consequence :+ outcome) ++ finish)

  private[logic] def resolveContact(state: MatchState, balls: MatchBalls, first: Athlete, second: Athlete,
      kind: ContactKind, impact: FlightRules.ContactImpact): (MatchState, MatchBalls, List[MatchEvent]) =
    val target = impact.target
    val changed = state.copy(athletes = state.athletes.updated(second.id, target).updated(first.id, impact.source))
    val foulKind = kind match
      case ContactKind.Grab => Some(FoulKind.Grabbing)
      case ContactKind.Charge if impact.axis.dot(target.flight.facing.value) > .65 => Some(FoulKind.RearCharge)
      case ContactKind.Charge if impact.impulse >= 25 => Some(FoulKind.ExcessiveCharge)
      case _ => None
    val hasBall = balls.quaffle == QuaffleState.HeldBy(second.id)
    foulKind match
      case Some(reason) =>
        val point = target.flight.position
        val attackingX = if second.side == Side.Home then point.value.x else -point.value.x
        val clearChance = hasBall && attackingX >= 31 && math.abs(point.value.y) < 10
        val foul = Foul(first.id, second.id, reason, point, clearChance)
        val count = state.fouls.getOrElse(first.id, 0) + 1
        val restart = foulRestart(second.side, foul, count)
        (changed.copy(fouls = changed.fouls.updated(first.id, count),
          phase = MatchPhase.RestartPending(restart, balls.special)), balls, List(MatchEvent.FoulCalled(foul)))
      case None =>
        val shield = second.ratings.ballControl.shielding.value
        val threshold = (if target.movement == MovementMode.Bracing then 48 else 65) - shield * .26
        if hasBall && target.stability.value < threshold then
          val dropped = QuaffleRules.free(target.flight.position.value + impact.axis * .4, target.flight.velocity.value)
          (changed, balls.copy(quaffle = dropped), List(MatchEvent.QuafflePossessionChanged(
            QuafflePossessionOutcome.Dropped(second.id, dropped.motion))))
        else (changed, balls, Nil)

object MatchRules:
  val standard: MatchRules = MatchRules()

  private[logic] type PenaltyResult =
    MatchEvent.PenaltyScored | MatchEvent.PenaltyBlocked | MatchEvent.PenaltyMissed | MatchEvent.PenaltyExpired

  private[logic] val goalPoints = 10
  private[logic] def opponent(side: Side): Side = if side == Side.Home then Side.Away else Side.Home

  private[logic] def addGoal(score: Score, side: Side): Score =
    if side == Side.Home then score.copy(home = score.home + goalPoints)
    else score.copy(away = score.away + goalPoints)
