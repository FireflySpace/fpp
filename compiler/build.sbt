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

// Shared (org, artifact, version); JVM cross-builds with %%, Scala Native with %%%.
lazy val sharedDependencies = Seq(
  ("com.github.scopt", "scopt", "4.0.1"),
  ("io.circe", "circe-core", "0.14.3"),
  ("io.circe", "circe-generic", "0.14.3"),
  ("io.circe", "circe-parser", "0.14.3"),
  ("org.scala-lang.modules", "scala-parser-combinators", "2.1.1"),
  ("org.scala-lang.modules", "scala-xml", "2.1.0"),
)

lazy val jvmDependencies =
  sharedDependencies.map { case (o, a, v) => o %% a % v } :+
    ("org.scalatest" %% "scalatest" % "3.2.12" % "test")

lazy val nativeDependencies = Def.setting(
  sharedDependencies.map { case (o, a, v) => o %%% a % v }
)

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
      // FPP_PGO selects an explicit build path:
      //   unset     normal release/test build (no PGO; releaseFast with full LTO)
      //   generate  matching build instrumented to write raw profiles
      //   apply     matching build that consumes FPP_PGO_PROFILE, or the
      //             local pgo/fpp.profdata when FPP_PGO_PROFILE is unset.
      val (compile, link) = sys.env.get("FPP_PGO").map(_.trim).filter(_.nonEmpty) match {
        case Some("generate") => // instrumented
          val dir = sys.env.getOrElse("FPP_PGO_DIR", "/tmp/fpp-pgo")
          val rt = sys.env.get("FPP_PGO_RUNTIME").map(_.trim).filter(_.nonEmpty)
          (Seq("-fprofile-generate=" + dir),
            Seq("-fprofile-generate=" + dir) ++
              rt.toSeq.flatMap(a => Seq("-Wl,--whole-archive", a, "-Wl,--no-whole-archive")))
        case Some("apply") =>
          val localProf = (ThisBuild / baseDirectory).value / "pgo" / "fpp.profdata"
          val prof = sys.env.get("FPP_PGO_PROFILE").map(new java.io.File(_)).getOrElse(localProf)
          if (!prof.exists)
            sys.error("FPP_PGO=apply requires FPP_PGO_PROFILE or pgo/fpp.profdata")
          (Seq("-fprofile-use=" + prof.getAbsolutePath,
              "-Wno-profile-instr-out-of-date", "-Wno-profile-instr-unprofiled"),
            Seq.empty[String])
        case None =>
          (Seq.empty[String], Seq.empty[String])
        case Some(value) =>
          sys.error(s"Unsupported FPP_PGO value '$value'; use generate or apply")
      }
      val config = nativeConfig.value
      config.withMode(Mode.releaseFast).withLTO(LTO.full).withGC(GC.none).withLinkStubs(true)
        .withCompileOptions(config.compileOptions ++ compile)
        .withLinkingOptions(config.linkingOptions ++ macOSUnwindLinkerOptions ++ link)
    }
  )
  .dependsOn(nativeLib)
  .enablePlugins(ScalaNativePlugin)
