package services.users.api

import cats.effect.IO
import com.sun.net.httpserver.HttpExchange
import io.circe.Json
import java.sql.Connection
import java.time.Instant
import scala.collection.mutable
import services.system.api.NoRequestMessage
import services.users.routes.AuthRoutes
import services.users.tables.account.AccountTableInitializer

final case class InitializeUsersMessage()
    extends NoRequestMessage[((HttpExchange, Connection) => IO[(Int, Json)],
      (String, Connection) => IO[Option[String]])]:
  override def plan(connection: Connection)
      : IO[((HttpExchange, Connection) => IO[(Int, Json)], (String, Connection) => IO[Option[String]])] =
    AccountTableInitializer.initialize(connection).map { _ =>
      val sessions = mutable.Map.empty[String, (String, Instant)]
      val routes = AuthRoutes(sessions)
      val resolve = (token: String, db: Connection) => ResolveSessionMessage(token, sessions).plan(db)
      (routes.route, resolve)
    }
