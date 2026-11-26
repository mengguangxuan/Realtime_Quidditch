package services.matches.objects

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class SimulationTick(value: Long)

object SimulationTick:
  given Codec.AsObject[SimulationTick] = deriveCodec[SimulationTick]

enum MatchEvent:
  case QuafflePossessionChanged(outcome: QuafflePossessionOutcome)
  case QuaffleGoal(scoringSide: Side)
  case QuaffleBlocked(by: AthleteId)
  case QuafflePassed(by: AthleteId, receiver: AthleteId)
  case ContactAttempted(athlete: AthleteId, kind: ContactKind)
  case ContactResolved(athlete: AthleteId, opponent: AthleteId, kind: ContactKind)
  case ControlChanged(player: PlayerId, from: AthleteId, to: AthleteId)
  case FoulCalled(foul: Foul)
  case AttackExpired(side: Side)
  case PenaltyScored(shooter: AthleteId)
  case PenaltyMissed(shooter: AthleteId)
  case PenaltyBlocked(shooter: AthleteId, by: AthleteId)
  case PenaltyExpired(shooter: AthleteId)
  case MatchEnded(reason: FinishReason)
  case ControlRejected(athlete: AthleteId, reason: ActionRejection)

object MatchEvent:
  given Codec.AsObject[MatchEvent] = deriveCodec[MatchEvent]

final case class TimelineFrame(tick: SimulationTick, state: MatchState, events: List[MatchEvent])

object TimelineFrame:
  given Codec.AsObject[TimelineFrame] = deriveCodec[TimelineFrame]

final case class MatchTimeline(matchId: MatchId, frames: Vector[TimelineFrame])

object MatchTimeline:
  given Codec.AsObject[MatchTimeline] = deriveCodec[MatchTimeline]
