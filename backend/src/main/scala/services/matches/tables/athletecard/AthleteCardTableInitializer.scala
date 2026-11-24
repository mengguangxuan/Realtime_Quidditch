package services.matches.tables.athletecard

import cats.effect.IO
import java.sql.Connection
import scala.io.Source
import scala.util.Using

object AthleteCardTableInitializer:
  private val createTableSql =
    """CREATE TABLE IF NOT EXISTS athlete_cards (
      |  athlete_id INTEGER PRIMARY KEY,
      |  card TEXT NOT NULL CHECK (json_valid(card) AND json_extract(card, '$.id') IS athlete_id)
      |)""".stripMargin

  private val seedSql =
    """INSERT INTO athlete_cards(athlete_id, card)
      |SELECT json_extract(value, '$.id'), value FROM json_each(?) WHERE true
      |ON CONFLICT(athlete_id) DO NOTHING""".stripMargin

  def initialize(connection: Connection): IO[Unit] = IO.blocking {
    val defaults = Using.resource(Source.fromResource("matches/athlete-cards.json"))(_.mkString)
    Using.resource(connection.createStatement())(_.executeUpdate(createTableSql))
    Using.resource(connection.prepareStatement(seedSql)) { statement =>
      statement.setString(1, defaults)
      statement.executeUpdate()
      ()
    }
  }
