package services.users.tables.account

import cats.effect.IO
import java.sql.Connection
import scala.util.Using

private[users] object AccountTable:
  private val insertSql =
    "INSERT INTO accounts(username, salt, password_hash) VALUES (?, ?, ?) ON CONFLICT(username) DO NOTHING"

  def insert(connection: Connection, username: String, salt: Array[Byte], hash: Array[Byte]): IO[Boolean] =
    IO.blocking {
      Using.resource(connection.prepareStatement(insertSql)) { statement =>
        statement.setString(1, username)
        statement.setBytes(2, salt)
        statement.setBytes(3, hash)
        statement.executeUpdate() == 1
      }
    }

  private val findSql = "SELECT salt, password_hash FROM accounts WHERE username = ?"

  def credentials(connection: Connection, username: String): IO[Option[(Array[Byte], Array[Byte])]] = IO.blocking {
    Using.resource(connection.prepareStatement(findSql)) { statement =>
      statement.setString(1, username)
      Using.resource(statement.executeQuery()) { rows =>
        Option.when(rows.next())(rows.getBytes("salt") -> rows.getBytes("password_hash"))
      }
    }
  }
