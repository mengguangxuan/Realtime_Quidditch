ThisBuild / scalaVersion := "3.3.3"

lazy val root = (project in file(".")).settings(
  name := "realtime-quidditch",
  Compile / mainClass := Some("services.system.Main"),
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-generic" % "0.14.9",
    "io.circe" %% "circe-parser" % "0.14.9"
  )
)
