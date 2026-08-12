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
      // PGO via env FPP_PGO; see compiler/pgo/README.md.
      val localProf = (ThisBuild / baseDirectory).value / "pgo" / "fpp.profdata"
      val (mode, lto, compile, link) = sys.env.get("FPP_PGO").map(_.trim).filter(_.nonEmpty) match {
        case Some("generate") => // instrumented
          val dir = sys.env.getOrElse("FPP_PGO_DIR", "/tmp/fpp-pgo")
          val rt = sys.env.get("FPP_PGO_RUNTIME").map(_.trim).filter(_.nonEmpty)
          (Mode.releaseFast, LTO.thin, Seq("-fprofile-generate=" + dir),
            Seq("-fprofile-generate=" + dir) ++
              rt.toSeq.flatMap(a => Seq("-Wl,--whole-archive", a, "-Wl,--no-whole-archive")))
        case other => // optimized, applying a profile when present
          val prof = other.map(new java.io.File(_)).filter(_.exists).orElse(Some(localProf).filter(_.exists))
          (Mode.releaseFull, LTO.full,
            prof.toSeq.flatMap(p => Seq("-fprofile-use=" + p.getAbsolutePath,
              "-Wno-profile-instr-out-of-date", "-Wno-profile-instr-unprofiled")),
            Seq.empty[String])
      }
      val config = nativeConfig.value
      config.withMode(mode).withLTO(lto).withGC(GC.none).withLinkStubs(true)
        .withCompileOptions(config.compileOptions ++ Seq("-O3") ++ compile)
        .withLinkingOptions(config.linkingOptions ++ macOSUnwindLinkerOptions ++ link)
    }
  )
  .dependsOn(nativeLib)
  .enablePlugins(ScalaNativePlugin)
