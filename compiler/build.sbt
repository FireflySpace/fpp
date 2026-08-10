// See https://pbassiner.github.io/blog/defining_multi-project_builds_with_sbt.html
import scala.scalanative.build._

name := "fpp-compiler"
ThisBuild / organization := "gov.nasa.jpl"
ThisBuild / scalaVersion := "3.1.2"
ThisBuild / dependencyOverrides +=
  "org.scala-lang" %% "scala3-library" % "3.1.2"

lazy val settings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-Xmax-inlines:100"
  ),
  libraryDependencies ++= dependencies.value,
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oNCXELOPQRM"),
  Test / parallelExecution := false,
)

lazy val dependencies = Def.setting(Seq(
  "com.github.scopt" %%% "scopt" % "4.0.1",
  "io.circe" %%% "circe-core" % "0.14.3",
  "io.circe" %%% "circe-generic" % "0.14.3",
  "io.circe" %%% "circe-parser" % "0.14.3",
  "org.scala-lang.modules" %%% "scala-parser-combinators" % "2.1.1",
  "org.scala-lang.modules" %%% "scala-xml" % "2.1.0",
  "org.scalatest" %% "scalatest" % "3.2.12" % "test",
))

lazy val root = (project in file("."))
  .settings(settings)
  .aggregate(
    lib,
    fpp
  )

lazy val lib = project
  .settings(settings)
  .enablePlugins(ScalaNativePlugin)

lazy val fpp = (project in file("tools/fpp"))
  .settings(settings)
  .settings(
    nativeConfig ~= { config =>
      config.withLTO(LTO.thin).withMode(Mode.releaseFast).withGC(GC.none)
        .withLinkStubs(true)
    }
  )
  .dependsOn(lib)
  .enablePlugins(ScalaNativePlugin)
