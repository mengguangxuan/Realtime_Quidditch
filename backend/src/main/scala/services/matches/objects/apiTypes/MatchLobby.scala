package services.matches.objects.apiTypes

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import services.matches.objects.{Athlete, TeamTactics}

final case class MatchLobby(cards: List[Athlete], lineups: Map[String, LobbyLineup], teamSize: Int,
    watch: Boolean, active: Boolean, homeTactics: TeamTactics, positioning: LobbyPositioning)

object MatchLobby:
  given Codec.AsObject[MatchLobby] = deriveCodec[MatchLobby]
