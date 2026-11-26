package services.system

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpHandler, HttpServer}
import java.net.InetSocketAddress
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.util.control.NonFatal
import services.matches.api.InitializeMatchMessage
import services.users.api.InitializeUsersMessage

object Main:
  def main(args: Array[String]): Unit =
    val port = args.headOption.fold(8080)(_.toInt)
    val mode = args.lift(1).getOrElse("cpu")
    val shotSeed = args.lift(2).flatMap(_.toLongOption)
    val teamSize = args.lift(3).fold(1)(_.toInt)
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
          matchRuntime <- InitializeMatchMessage(mode, shotSeed, teamSize).plan(connection)
          (id, matchRoutes, advance) = matchRuntime
          usersRuntime <- InitializeUsersMessage().plan(connection)
          (userRoutes, resolveUser) = usersRuntime
          handler = applicationHandler(path, userRoutes, resolveUser, matchRoutes)
          _ <- IO.println(s"Recording match ${id.value} in $path")
          _ <- IO.blocking(serve(port, handler, advance, stopped))
        yield ()
      }.unsafeRunSync()
    finally closed.countDown()

  private def serve(port: Int, handler: HttpHandler, advance: IO[Unit], stopped: CountDownLatch): Unit =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    val clock = Executors.newSingleThreadScheduledExecutor()
    server.createContext("/", handler)
    server.start()
    clock.scheduleAtFixedRate(
      (() =>
        try advance.unsafeRunSync()
        catch case NonFatal(error) => System.err.println(s"Simulation step failed: ${error.getMessage}")
      ): Runnable, 50, 50, TimeUnit.MILLISECONDS
    )
    println(s"Quidditch demo listening at http://127.0.0.1:${server.getAddress.getPort}")
    try stopped.await()
    finally
      clock.shutdown()
      server.stop(0)
      clock.awaitTermination(5, TimeUnit.SECONDS)
