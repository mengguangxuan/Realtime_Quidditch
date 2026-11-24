package services.matches.objects

import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.deriveCodec

final case class SimulationTick(value: Long)

object SimulationTick:
  given Codec.AsObject[SimulationTick] = deriveCodec[SimulationTick]

final case class PlayTick(value: Long)

object PlayTick:
  given Codec.AsObject[PlayTick] = deriveCodec[PlayTick]

final case class MatchClock(elapsed: PlayTick, durationTicks: Long)

object MatchClock:
  given Codec.AsObject[MatchClock] = deriveCodec[MatchClock]

enum MatchEvent:
  case QuafflePossessionChanged(outcome: QuafflePossessionOutcome)
  case QuaffleGoal(scoringSide: Side)
  case QuaffleBlocked(by: AthleteId)
  case QuafflePassed(by: AthleteId, receiver: AthleteId)
  case ContactAttempted(athlete: AthleteId, kind: ContactKind)
  case ContactResolved(athlete: AthleteId, opponent: AthleteId, kind: ContactKind)
  case ControlChanged(player: PlayerId, from: AthleteId, to: AthleteId)
  case FoulCalled(foul: Foul)
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
