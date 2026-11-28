package services.matches.tables.athletecard

import cats.effect.IO
import java.sql.Connection
import scala.io.Source
import scala.util.Using

private[matches] object AthleteCardTableInitializer:
  private val createTableSql =
    """CREATE TABLE IF NOT EXISTS athlete_cards (
      |  athlete_id INTEGER PRIMARY KEY,
      |  card TEXT NOT NULL CHECK (json_valid(card) AND json_extract(card, '$.id') IS athlete_id)
      |)""".stripMargin

  private val seedSql =
    """INSERT INTO athlete_cards(athlete_id, card)
      |SELECT json_extract(value, '$.id'), value FROM json_each(?) WHERE true
      |ON CONFLICT(athlete_id) DO UPDATE
      |SET card = json_set(athlete_cards.card, '$.ratings', json(json_extract(excluded.card, '$.ratings')))
      |WHERE athlete_cards.athlete_id BETWEEN 12 AND 18
      |  AND json_extract(athlete_cards.card, '$.name') = json_extract(excluded.card, '$.name')
      |  AND json_extract(athlete_cards.card, '$.side') = json_extract(excluded.card, '$.side')
      |  AND NOT EXISTS (
      |    SELECT 1 FROM json_tree(excluded.card, '$.ratings') AS rating
      |    WHERE rating.type != 'object' AND json_extract(athlete_cards.card, rating.fullkey) IS NOT 75
      |  )
      |  AND (SELECT count(*) FROM json_tree(athlete_cards.card, '$.ratings') WHERE type != 'object')
      |    = (SELECT count(*) FROM json_tree(excluded.card, '$.ratings') WHERE type != 'object')""".stripMargin

  def initialize(connection: Connection): IO[Unit] = IO.blocking {
    val defaults = Using.resource(Source.fromResource("matches/athlete-cards.json"))(_.mkString)
    Using.resource(connection.createStatement())(_.executeUpdate(createTableSql))
    Using.resource(connection.prepareStatement(seedSql)) { statement =>
      statement.setString(1, defaults)
      statement.executeUpdate()
      ()
    }
  }
