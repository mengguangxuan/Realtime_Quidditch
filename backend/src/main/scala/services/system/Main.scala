package services.system

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.util.control.NonFatal
import services.matches.logic.MatchSession
import services.matches.routes.MatchRoutes

object Main:
  def main(args: Array[String]): Unit =
    val port = args.headOption.fold(8080)(_.toInt)
    val computerOpponent = args.lift(1).contains("cpu")
    val session = args.lift(2).flatMap(_.toLongOption) match
      case Some(seed) => MatchSession.demo(computerOpponent, seed)
      case None => MatchSession.demo(computerOpponent)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    val clock = Executors.newSingleThreadScheduledExecutor()
    val stopped = CountDownLatch(1)
    server.createContext("/", MatchRoutes(session))
    Runtime.getRuntime.addShutdownHook(new Thread(() => stopped.countDown()))
    server.start()
    clock.scheduleAtFixedRate(
      (() =>
        try { session.advance(); () }
        catch case NonFatal(error) => System.err.println(s"Simulation step failed: ${error.getMessage}")
      ): Runnable, 50, 50, TimeUnit.MILLISECONDS
    )
    println(s"Quidditch demo listening at http://127.0.0.1:${server.getAddress.getPort}")
    try stopped.await()
    catch case _: InterruptedException => Thread.currentThread().interrupt()
    finally
      clock.shutdownNow()
      server.stop(0)
