package services.matches.objects

import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.deriveCodec

final case class MatchId(value: String)

object MatchId:
  given Codec[MatchId] = Codec.from(Decoder.decodeString.map(MatchId.apply), Encoder.encodeString.contramap(_.value))
  given KeyEncoder[MatchId] = KeyEncoder.instance(_.value.toString)
  given KeyDecoder[MatchId] = KeyDecoder.instance(value => Some(MatchId(value)))

final case class MatchSetup(
  roster: List[Athlete],
  players: List[Player],
  roles: Map[AthleteId, AthleteRole],
  assignments: List[ControlAssignment],
  balls: BallSetup
)

object MatchSetup:
  given Codec.AsObject[MatchSetup] = deriveCodec[MatchSetup]

final case class Score(home: Int, away: Int)

object Score:
  given Codec.AsObject[Score] = deriveCodec[Score]

enum FinishReason:
  case TimeExpired
  case SnitchCaught(by: AthleteId)

object FinishReason:
  given Codec.AsObject[FinishReason] = deriveCodec[FinishReason]

enum ContactKind:
  case Incidental, Charge, Grab

object ContactKind:
  given Codec[ContactKind] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown ContactKind: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

enum FoulKind:
  case RearCharge, ExcessiveCharge, Grabbing

object FoulKind:
  given Codec[FoulKind] = Codec.from(
    Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Unknown FoulKind: $value")),
    Encoder.encodeString.contramap(_.toString)
  )

final case class Foul(
  offender: AthleteId,
  victim: AthleteId,
  kind: FoulKind,
  position: Position,
  deniedClearChance: Boolean
)

object Foul:
  given Codec.AsObject[Foul] = deriveCodec[Foul]

enum Restart:
  case Kickoff(side: Side)
  case FreePossession(side: Side, at: Position, foul: Foul)
  case PenaltyThrow(side: Side, foul: Foul)

object Restart:
  given Codec.AsObject[Restart] = deriveCodec[Restart]

enum MatchPhase:
  case Ready
  case InPlay(balls: MatchBalls)
  case RestartPending(restart: Restart, suspended: SpecialBalls)
  case Finished(reason: FinishReason)

object MatchPhase:
  given Codec.AsObject[MatchPhase] = deriveCodec[MatchPhase]

final case class MatchState(
  id: MatchId,
  setup: MatchSetup,
  controls: List[ControlAssignment],
  athletes: Map[AthleteId, AthleteState],
  score: Score,
  clock: MatchClock,
  fouls: Map[AthleteId, Int],
  phase: MatchPhase
)

object MatchState:
  given Codec.AsObject[MatchState] = deriveCodec[MatchState]
