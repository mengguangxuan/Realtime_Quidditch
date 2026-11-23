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
import services.matches.tables.timeline.{TimelineTable, TimelineTableInitializer}

object Main:
  def main(args: Array[String]): Unit =
    val port = args.headOption.fold(8080)(_.toInt)
    val mode = args.lift(1)
    val computerHome = mode.contains("watch")
    val computerOpponent = computerHome || mode.contains("cpu")
    val shotSeed = args.lift(2).flatMap(_.toLongOption).getOrElse(ThreadLocalRandom.current().nextLong())
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
        TimelineTableInitializer.initialize(connection) *> IO.blocking {
          val id = MatchId(UUID.randomUUID().toString)
          val session = MatchSession.demo(computerOpponent, shotSeed, computerHome, id,
            frame => TimelineTable.insert(connection, frame).unsafeRunSync())
          println(s"Recording match ${id.value} in $path")
          serve(port, session, path, stopped)
        }
      }.unsafeRunSync()
    finally closed.countDown()

  private def serve(port: Int, session: MatchSession, path: Path, stopped: CountDownLatch): Unit =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    val clock = Executors.newSingleThreadScheduledExecutor()
    server.createContext("/", MatchRoutes(session, Some(path)))
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
