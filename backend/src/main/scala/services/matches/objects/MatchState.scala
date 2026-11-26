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
