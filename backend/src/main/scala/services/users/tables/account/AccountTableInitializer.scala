package services.users.tables.account

import cats.effect.IO
import java.sql.Connection
import scala.util.Using

object AccountTableInitializer:
  private val createTableSql =
    """CREATE TABLE IF NOT EXISTS accounts (
      |  username TEXT PRIMARY KEY NOT NULL,
      |  salt BLOB NOT NULL,
      |  password_hash BLOB NOT NULL
      |)""".stripMargin

  def initialize(connection: Connection): IO[Unit] = IO.blocking {
    Using.resource(connection.createStatement())(_.executeUpdate(createTableSql))
    ()
  }
