package services.matches.api

import cats.effect.IO
import java.sql.Connection
import services.matches.logic.{MatchSession, MatchSimulation}
import services.matches.objects.{MatchPhase, Pitch, PlayerKind, Side}
import services.matches.objects.apiTypes.{LobbyLineup, LobbyPositioning, MatchLobby}
import services.matches.tables.athletecard.AthleteCardTable
import services.system.api.NoRequestMessage

final case class GetLobbyMessage(private val session: MatchSession) extends NoRequestMessage[MatchLobby]:
  override def plan(connection: Connection): IO[MatchLobby] =
    for
      available <- AthleteCardTable.list(connection)
      state <- IO.delay(session.latest.state)
    yield
      val setup = state.setup
      val smallRoster = MatchSession.demoCards(available, 2)
      val lineups = MatchSession.demoTeamSizes.map { size =>
        val slots = List.tabulate(size)(index => MatchSimulation.startingSlot(Side.Home, index, size))
        val keepers = Side.values.toList.flatMap(side => MatchSession.demoKeeper(side, size))
        size.toString -> LobbyLineup(MatchSession.demoCards(available, size), slots, keepers,
          MatchSession.demoReserves(available, size))
      }.toMap
      val pitch = Pitch.alpine
      val smallSlots = List.tabulate(smallRoster.count(_.side == Side.Home))(index =>
        MatchSimulation.startingSlot(Side.Home, index))
      val positioning = LobbyPositioning(smallSlots, pitch.halfLength, pitch.halfWidth, pitch.goalPlaneX,
        MatchSimulation.startingClearance, MatchSimulation.startingSpacing)
      val active = state.phase match
        case MatchPhase.Ready | MatchPhase.Finished(_) => false
        case _ => true
      MatchLobby(smallRoster, lineups, setup.roster.size / 2, setup.players.forall(_.kind == PlayerKind.Computer),
        active, setup.tacticsFor(Side.Home), positioning, setup.swapTeams.contains(true))
