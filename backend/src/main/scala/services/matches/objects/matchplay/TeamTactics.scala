package services.matches.objects

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

enum AttackSupport:
  case Nearby, Wide

object AttackSupport:
  given Codec[AttackSupport] = Codec.from(
    Decoder.decodeString.emap(name => values.find(_.toString == name).toRight("Unknown attack support")),
    Encoder.encodeString.contramap(_.toString)
  )

enum DefensiveApproach:
  case Pressure, Cover

object DefensiveApproach:
  given Codec[DefensiveApproach] = Codec.from(
    Decoder.decodeString.emap(name => values.find(_.toString == name).toRight("Unknown defensive approach")),
    Encoder.encodeString.contramap(_.toString)
  )

final case class TeamTactics(attackSupport: AttackSupport, defense: DefensiveApproach)

object TeamTactics:
  val default: TeamTactics = TeamTactics(AttackSupport.Wide, DefensiveApproach.Pressure)
  given Codec.AsObject[TeamTactics] = deriveCodec[TeamTactics]

final case class MatchTactics(home: TeamTactics, away: TeamTactics):
  def forSide(side: Side): TeamTactics = side match
    case Side.Home => home
    case Side.Away => away

object MatchTactics:
  given Codec.AsObject[MatchTactics] = deriveCodec[MatchTactics]
