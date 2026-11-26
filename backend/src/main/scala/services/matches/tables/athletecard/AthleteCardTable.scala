package services.matches.tables.athletecard

import cats.effect.IO
import io.circe.parser.decode
import java.sql.Connection
import scala.util.Using
import services.matches.objects.Athlete

private[matches] object AthleteCardTable:
  private val listSql = "SELECT athlete_id, card FROM athlete_cards ORDER BY athlete_id"

  def list(connection: Connection): IO[List[Athlete]] = IO.blocking {
    Using.resource(connection.prepareStatement(listSql)) { statement =>
      Using.resource(statement.executeQuery()) { rows =>
        val cards = List.newBuilder[Athlete]
        while rows.next() do
          val athleteId = rows.getInt("athlete_id")
          val card = decode[Athlete](rows.getString("card")).fold(
            error => throw IllegalArgumentException(s"Invalid athlete card $athleteId", error),
            identity
          )
          cards += card
        cards.result()
      }
    }
  }
