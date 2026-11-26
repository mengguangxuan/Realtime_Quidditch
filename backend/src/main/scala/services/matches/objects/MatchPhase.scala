package services.matches.objects

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

enum FinishReason:
  case TimeExpired
  case SnitchCaught(by: AthleteId)

object FinishReason:
  given Codec.AsObject[FinishReason] = deriveCodec[FinishReason]

enum Restart:
  case Kickoff(side: Side)
  case TeamPossession(side: Side, at: Position, after: MatchEvent)
  case FreePossession(side: Side, at: Position, foul: Foul)
  case PenaltyThrow(side: Side, foul: Foul)

object Restart:
  given Codec.AsObject[Restart] = deriveCodec[Restart]

enum MatchPhase:
  case Ready
  case InPlay(balls: MatchBalls)
  case Penalty(balls: MatchBalls, side: Side, shooter: AthleteId, remainingTicks: Long)
  case RestartPending(restart: Restart, suspended: SpecialBalls)
  case Finished(reason: FinishReason)

object MatchPhase:
  given Codec.AsObject[MatchPhase] = deriveCodec[MatchPhase]
