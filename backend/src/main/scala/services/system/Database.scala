package services.system

import cats.effect.{IO, Resource}
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import org.sqlite.SQLiteConfig

object Database:
  def path: Path =
    Path.of(sys.env.getOrElse("QUIDDITCH_DB_PATH", "data/matches.sqlite3")).toAbsolutePath.normalize()

  def connect(path: Path): Resource[IO, Connection] =
    Resource.fromAutoCloseable(IO.blocking {
      Files.createDirectories(path.getParent)
      val config = new SQLiteConfig()
      config.setBusyTimeout(5000)
      config.setJournalMode(SQLiteConfig.JournalMode.WAL)
      DriverManager.getConnection(s"jdbc:sqlite:$path", config.toProperties)
    })
