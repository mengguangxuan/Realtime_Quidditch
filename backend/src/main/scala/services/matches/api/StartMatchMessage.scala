package services.matches.api

import cats.effect.IO
import java.sql.Connection
import services.matches.logic.MatchSession
import services.matches.objects.{ActionRejection, MatchSetup, TeamTactics, TimelineFrame}
import services.matches.objects.apiTypes.MatchSelection
import services.matches.tables.athletecard.AthleteCardTable
import services.system.api.APIMessage

final case class StartMatchMessage(selection: Either[String, Option[MatchSelection]], username: Option[String],
    private val session: MatchSession, private val computerOpponent: Boolean)
    extends APIMessage[Either[String | ActionRejection, TimelineFrame]]:
  override def plan(connection: Connection): IO[Either[String | ActionRejection, TimelineFrame]] =
    IO.delay(username.forall(session.mayStart)).flatMap {
      case false => IO.pure(Left("match_unavailable"))
      case true =>
        val lineup: IO[Either[String, Option[MatchSetup]]] = selection match
          case Left(reason) => IO.pure(Left(reason))
          case Right(None) => IO.pure(Right(None))
          case Right(Some(choice)) => AthleteCardTable.list(connection).map { cards =>
            MatchSession.demoSetup(cards, computerOpponent || choice.watch, choice.watch,
              choice.teamSize, Some(choice.athlete), choice.tactics.getOrElse(TeamTactics.default),
              choice.startingPositions).map(Some(_))
          }
        lineup.flatMap {
          case Left(reason) => IO.pure(Left(reason))
          case Right(setup) => IO.blocking(session.startFor(username, setup))
        }
    }
