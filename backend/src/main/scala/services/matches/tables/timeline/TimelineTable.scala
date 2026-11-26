package services.matches.tables.timeline

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import java.sql.Connection
import scala.util.Using
import services.matches.objects.{MatchId, SimulationTick, TimelineFrame}

private[matches] object TimelineTable:
  private val insertSql = "INSERT INTO match_timeline(match_id, tick, frame) VALUES (?, ?, ?)"

  def insert(connection: Connection, frame: TimelineFrame): IO[Unit] = IO.blocking {
    Using.resource(connection.prepareStatement(insertSql)) { statement =>
      statement.setString(1, frame.state.id.value)
      statement.setLong(2, frame.tick.value)
      statement.setString(3, frame.asJson.noSpaces)
      statement.executeUpdate()
      ()
    }
  }

  private val findSql = "SELECT frame FROM match_timeline WHERE match_id = ? AND tick = ?"

  def find(connection: Connection, id: MatchId, tick: SimulationTick): IO[Option[TimelineFrame]] = IO.blocking {
    Using.resource(connection.prepareStatement(findSql)) { statement =>
      statement.setString(1, id.value)
      statement.setLong(2, tick.value)
      Using.resource(statement.executeQuery()) { rows =>
        if rows.next() then Some(decode[TimelineFrame](rows.getString(1)).fold(error => throw error, identity))
        else None
      }
    }
  }

  private val listMatchesSql = "SELECT match_id FROM match_timeline WHERE tick = 0 ORDER BY rowid DESC"

  def listMatches(connection: Connection): IO[List[MatchId]] = IO.blocking {
    Using.resource(connection.prepareStatement(listMatchesSql)) { statement =>
      Using.resource(statement.executeQuery()) { rows =>
        val matches = List.newBuilder[MatchId]
        while rows.next() do matches += MatchId(rows.getString(1))
        matches.result()
      }
    }
  }
