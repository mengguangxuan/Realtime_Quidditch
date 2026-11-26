package services.matches.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpExchange
import io.circe.Json
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import services.matches.logic.MatchSession
import services.matches.objects.MatchId
import services.matches.routes.MatchRoutes
import services.matches.tables.athletecard.{AthleteCardTable, AthleteCardTableInitializer}
import services.matches.tables.timeline.{TimelineTable, TimelineTableInitializer}
import services.system.api.APIMessage

final case class InitializeMatchMessage(mode: String, seed: Option[Long], teamSize: Int)
    extends APIMessage[(MatchId, (HttpExchange, Option[String], Connection) => IO[(Int, Json)], IO[Unit])]:
  override def plan(connection: Connection)
      : IO[(MatchId, (HttpExchange, Option[String], Connection) => IO[(Int, Json)], IO[Unit])] =
    val computerOpponent = mode != "human"
    for
      _ <- IO.delay(require(MatchSession.demoTeamSizes.contains(teamSize), "Demo team size must be 1, 2 or 7"))
      _ <- TimelineTableInitializer.initialize(connection)
      _ <- AthleteCardTableInitializer.initialize(connection)
      cards <- AthleteCardTable.list(connection)
      instance <- IO.blocking {
        val id = MatchId(UUID.randomUUID().toString)
        val shotSeed = seed.getOrElse(ThreadLocalRandom.current().nextLong())
        val session = MatchSession.demo(cards, computerOpponent, shotSeed, mode == "watch", id,
          frame => TimelineTable.insert(connection, frame).unsafeRunSync(), teamSize)
        val routes = MatchRoutes(session, computerOpponent)
        (id, routes.route, IO.blocking(session.advance()).void)
      }
    yield instance
