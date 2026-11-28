package services.matches.objects

import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto.deriveCodec

final case class MatchId(value: String)

object MatchId:
  given Codec[MatchId] = Codec.from(Decoder.decodeString.map(MatchId.apply), Encoder.encodeString.contramap(_.value))
  given KeyEncoder[MatchId] = KeyEncoder.instance(_.value.toString)
  given KeyDecoder[MatchId] = KeyDecoder.instance(value => Some(MatchId(value)))

final case class Score(home: Int, away: Int)

object Score:
  given Codec.AsObject[Score] = deriveCodec[Score]

final case class MatchState(
  id: MatchId,
  setup: MatchSetup,
  controls: List[ControlAssignment],
  athletes: Map[AthleteId, AthleteState],
  score: Score,
  clock: MatchClock,
  fouls: Map[AthleteId, Int],
  phase: MatchPhase,
  attackClock: Option[MatchClock.Attack] = None
)

object MatchState:
  given Codec.AsObject[MatchState] = deriveCodec[MatchState]

final case class MatchSetup(
  roster: List[Athlete],
  players: List[Player],
  roles: Map[AthleteId, AthleteRole],
  assignments: List[ControlAssignment],
  balls: BallSetup,
  tactics: Option[MatchTactics] = None,
  startingPositions: Option[Map[AthleteId, Position]] = None,
  swapTeams: Option[Boolean] = None
):
  def tacticsFor(side: Side): TeamTactics =
    tactics.fold(TeamTactics.default)(_.forSide(side))

object MatchSetup:
  given Codec.AsObject[MatchSetup] = deriveCodec[MatchSetup]

enum FinishReason:
  case TimeExpired
  case SnitchCaught(by: AthleteId)
  case EndedByUser

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
