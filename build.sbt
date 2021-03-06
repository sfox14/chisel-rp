
val chiselVersion = System.getProperty("chiselVersion", "latest.release")

lazy val rpSettings = Seq(
  organization := "usyd.edu.au",

  version := "0.1",

  name := "randomProjections",

  scalaVersion := "2.11.7",

  ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },

  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-language:reflectiveCalls"),
  resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/releases/",
  parallelExecution in Test := false,
  libraryDependencies ++= ( if (chiselVersion != "None" ) ("edu.berkeley.cs" %% "chisel" % chiselVersion) :: Nil; else Nil),
  libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "1.2.1",
  libraryDependencies += "com.novocode" % "junit-interface" % "0.10" % "test",
  libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.11.7"
)

lazy val chisel = RootProject(uri("git://github.com/sfox14/chisel.git"))
lazy val olk = RootProject(uri("git://github.com/da-steve101/chisel-pipelined-olk.git"))

lazy val randp = (project in file(".")).settings(rpSettings: _*).dependsOn(chisel).dependsOn(olk)

