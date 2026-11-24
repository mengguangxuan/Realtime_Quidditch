package services.system

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, ThreadLocalRandom, TimeUnit}
import scala.util.control.NonFatal
import services.matches.logic.MatchSession
import services.matches.objects.MatchId
import services.matches.routes.MatchRoutes
import services.matches.tables.athletecard.{AthleteCardTable, AthleteCardTableInitializer}
import services.matches.tables.timeline.{TimelineTable, TimelineTableInitializer}
import services.users.routes.AuthRoutes
import services.users.tables.account.AccountTableInitializer

object Main:
  def main(args: Array[String]): Unit =
    val port = args.headOption.fold(8080)(_.toInt)
    val mode = args.lift(1).getOrElse("cpu")
    val computerHome = mode == "watch"
    val computerOpponent = mode != "human"
    val shotSeed = args.lift(2).flatMap(_.toLongOption).getOrElse(ThreadLocalRandom.current().nextLong())
    val teamSize = args.lift(3).fold(1)(_.toInt)
    require(teamSize >= 1 && teamSize <= 2, "Demo team size must be 1 or 2")
    val path = Database.path
    val stopped = CountDownLatch(1)
    val closed = CountDownLatch(1)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      stopped.countDown()
      closed.await(5, TimeUnit.SECONDS)
      ()
    }))
    try
      Database.connect(path).use { connection =>
        for
          _ <- TimelineTableInitializer.initialize(connection)
          _ <- AthleteCardTableInitializer.initialize(connection)
          _ <- AccountTableInitializer.initialize(connection)
          cards <- AthleteCardTable.list(connection)
          _ <- IO.blocking {
            val id = MatchId(UUID.randomUUID().toString)
            val session = MatchSession.demo(cards, computerOpponent, shotSeed, computerHome, id,
              frame => TimelineTable.insert(connection, frame).unsafeRunSync(), teamSize)
            println(s"Recording match ${id.value} in $path")
            serve(port, session, path, stopped, computerOpponent)
          }
        yield ()
      }.unsafeRunSync()
    finally closed.countDown()

  private def serve(port: Int, session: MatchSession, path: Path, stopped: CountDownLatch,
      computerOpponent: Boolean): Unit =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    val clock = Executors.newSingleThreadScheduledExecutor()
    server.createContext("/", AuthRoutes(path,
      MatchRoutes(session, Some(path), localControlsOnly = true, computerOpponent = computerOpponent)))
    server.start()
    clock.scheduleAtFixedRate(
      (() =>
        try { session.advance(); () }
        catch case NonFatal(error) => System.err.println(s"Simulation step failed: ${error.getMessage}")
      ): Runnable, 50, 50, TimeUnit.MILLISECONDS
    )
    println(s"Quidditch demo listening at http://127.0.0.1:${server.getAddress.getPort}")
    try stopped.await()
    finally
      clock.shutdown()
      server.stop(0)
      clock.awaitTermination(5, TimeUnit.SECONDS)
