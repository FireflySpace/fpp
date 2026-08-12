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
    "-Xfatal-warnings",
    "-Xmax-inlines:100"
  ),
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oNCXELOPQRM"),
)

lazy val jvmDependencies = Seq(
  "com.github.scopt" %% "scopt" % "4.0.1",
  "io.circe" %% "circe-core" % "0.14.3",
  "io.circe" %% "circe-generic" % "0.14.3",
  "io.circe" %% "circe-parser" % "0.14.3",
  "org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1",
  "org.scala-lang.modules" %% "scala-xml" % "2.1.0",
  "org.scalatest" %% "scalatest" % "3.2.12" % "test",
)

lazy val nativeDependencies = Def.setting(Seq(
  "com.github.scopt" %%% "scopt" % "4.0.1",
  "io.circe" %%% "circe-core" % "0.14.3",
  "io.circe" %%% "circe-generic" % "0.14.3",
  "io.circe" %%% "circe-parser" % "0.14.3",
  "org.scala-lang.modules" %%% "scala-parser-combinators" % "2.1.1",
  "org.scala-lang.modules" %%% "scala-xml" % "2.1.0",
))

lazy val jvmSettings = settings ++ Seq(
  libraryDependencies ++= jvmDependencies,
)

lazy val nativeSettings = settings ++ Seq(
  libraryDependencies ++= nativeDependencies.value,
  target := baseDirectory.value / "target-native",
)

lazy val macOSUnwindLinkerOptions =
  if (System.getProperty("os.name") == "Mac OS X") Seq(
    "-Wl,-u,___unw_regname",
    "-Wl,-u,___unw_iterate_dwarf_unwind_cache",
    "-Wl,-u,___unw_is_fpreg",
    "-Wl,-u,___unw_get_fpreg",
    "-Wl,-u,___unw_set_fpreg",
  )
  else Seq.empty

lazy val root = (project in file("."))
  .settings(settings)
  .aggregate(
    lib,
    fpp
  )

lazy val lib = project
  .settings(jvmSettings)

lazy val fpp = (project in file("tools/fpp"))
  .settings(jvmSettings)
  .dependsOn(lib)

lazy val nativeLib = (project in file("lib"))
  .settings(nativeSettings)
  .enablePlugins(ScalaNativePlugin)

lazy val nativeFpp = (project in file("tools/fpp"))
  .settings(nativeSettings)
  .settings(
    name := "fpp",
    nativeConfig := {
      val config = nativeConfig.value
      // Absolute path to the committed PGO profile (clang resolves it at compile time).
      val profile = ((ThisBuild / baseDirectory).value / "pgo" / "fpp.profdata")
      val pgoOpts =
        if (profile.exists) Seq("-fprofile-use=" + profile.getAbsolutePath,
                                "-Wno-profile-instr-out-of-date",
                                "-Wno-profile-instr-unprofiled")
        else Seq.empty[String]
      config.withLTO(LTO.full).withMode(Mode.releaseFull).withGC(GC.none)
        .withLinkStubs(true)
        .withCompileOptions(config.compileOptions ++ Seq("-O3") ++ pgoOpts)
        .withLinkingOptions(config.linkingOptions ++ macOSUnwindLinkerOptions)
    }
  )
  .dependsOn(nativeLib)
  .enablePlugins(ScalaNativePlugin)
