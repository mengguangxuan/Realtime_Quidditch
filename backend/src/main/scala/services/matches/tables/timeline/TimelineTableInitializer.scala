package services.matches.tables.timeline

import cats.effect.IO
import java.sql.Connection
import scala.util.Using

private[matches] object TimelineTableInitializer:
  private val createTableSql =
    """CREATE TABLE IF NOT EXISTS match_timeline (
      |  match_id TEXT NOT NULL,
      |  tick INTEGER NOT NULL CHECK (tick >= 0),
      |  frame TEXT NOT NULL,
      |  PRIMARY KEY (match_id, tick)
      |)""".stripMargin

  private val indexMatchesSql =
    "CREATE INDEX IF NOT EXISTS match_timeline_starts ON match_timeline(tick) WHERE tick = 0"

  def initialize(connection: Connection): IO[Unit] = IO.blocking {
    Using.resource(connection.createStatement()) { statement =>
      statement.executeUpdate(createTableSql)
      statement.executeUpdate(indexMatchesSql)
      ()
    }
  }
