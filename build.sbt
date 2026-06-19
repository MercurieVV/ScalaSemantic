ThisBuild / scalaVersion := "3.8.4"

ThisBuild / scalafixDependencies += "org.typelevel" %% "typelevel-scalafix" % "0.5.0"

// --- Publishing (Sonatype Central via sbt-ci-release) ----------------------------------------
// Version is derived from git tags by sbt-dynver: a `vX.Y.Z` tag publishes `X.Y.Z`. The Central
// namespace for a GitHub account is io.github.<user>. `ci-release` signs + uploads on tag pushes.
ThisBuild / organization := "io.github.mercurievv"
ThisBuild / organizationName := "mercurievv"
ThisBuild / homepage := Some(url("https://github.com/mercurievv/ScalaSemantic"))
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / developers := List(
  Developer("mercurievv", "Viktor Skalinins", "mercurievv@gmail.com", url("https://github.com/mercurievv"))
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/mercurievv/ScalaSemantic"),
    "scm:git:https://github.com/mercurievv/ScalaSemantic.git"
  )
)
ThisBuild / versionScheme := Some("early-semver")
// New Sonatype accounts publish through the Central Portal: the host is supplied to `ci-release`
// via the SONATYPE_CREDENTIAL_HOST=central.sonatype.com env var in the release workflow.

// Shared across all modules: SemanticDB emission (for dogfooding + scalafix) and wart rules.
lazy val commonSettings = Seq(
  scalacOptions += "-Wunused:imports", // required by OrganizeImports scalafix rule
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision,
  wartremoverWarnings ++= Seq(
    Wart.Var,
    Wart.MutableDataStructures,
    Wart.NonUnitStatements,
    Wart.Throw,
    Wart.Return,
    Wart.AsInstanceOf,
    Wart.IsInstanceOf,
    Wart.Null
  )
)

lazy val munit = "org.scalameta" %% "munit" % "1.2.3" % Test
lazy val upickle = "com.lihaoyi" %% "upickle" % "4.2.1"

// Generate a standalone, build-tool-agnostic launcher for the MCP server: a script that runs the
// server on a clean JVM (no sbt → no stdout pollution of the JSON-RPC stream).
lazy val mcpLauncher = taskKey[File]("Write a standalone MCP server launch script")
lazy val mcpClientConfig = taskKey[Unit]("Print the .mcp.json entry that registers this server")

// core: load + index SemanticDB and expose symbol-grammar primitives. No JSON, no MCP.
// scalameta + its SemanticDB bindings are JVM-published on the 2.13 line; consume them from
// Scala 3 via for3Use2_13 (as scalafix does). `semanticdb-shared` carries the
// scala.meta.internal.semanticdb.* protobuf classes (not pulled in by `scalameta`).
lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-core",
    libraryDependencies ++= Seq(
      ("org.scalameta" %% "scalameta" % "4.13.9").cross(CrossVersion.for3Use2_13),
      ("org.scalameta" %% "semanticdb-shared" % "4.13.9").cross(CrossVersion.for3Use2_13),
      munit
    )
  )

// analysis: the query engine + result models (upickle), built on core.
lazy val analysis = (project in file("analysis"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-analysis",
    libraryDependencies ++= Seq(upickle, munit)
  )

// mcp: stdio JSON-RPC server + entrypoint. Test-depends on analysis so its fixtures (and their
// SemanticDB) are compiled before the MCP tests, which dogfood on the whole repo's SemanticDB.
lazy val mcp = (project in file("mcp"))
  .dependsOn(analysis % "compile->compile;test->test")
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-mcp",
    // Surface the dynver-derived version to the server at runtime so `serverInfo.version` is real,
    // not a hardcoded literal. Generates BuildInfo in the package below.
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "com.github.mercurievv.scalasemantic.buildinfo",
    libraryDependencies ++= Seq(upickle, munit),
    // Pin the entrypoint (the module has two @main) so both the packaged Central jar and `cs launch`
    // resolve `mcpServer` without an explicit main-class flag.
    Compile / mainClass := Some("com.github.mercurievv.scalasemantic.mcpServer"),
    // Fat jar for `java -jar scalasemantic-mcp.jar <root>` — the install-free launch path attached
    // to GitHub Releases. Pin the main class (the module has two @main) so the manifest is correct.
    assembly / mainClass := Some("com.github.mercurievv.scalasemantic.mcpServer"),
    assembly / assemblyJarName := "scalasemantic-mcp.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*)
          if xs.lastOption.exists(s => s.endsWith(".SF") || s.endsWith(".DSA") || s.endsWith(".RSA")) =>
        MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case x                   => (assembly / assemblyMergeStrategy).value.apply(x)
    },
    // DEV-ONLY launcher: runs the server straight off this build's classpath (no jar). Written under
    // target/, so it is wiped by `clean` — do NOT reference it from a persistent .mcp.json. For that,
    // install the stable launcher (scripts/install.sh) and use the path `mcpClientConfig` prints.
    mcpLauncher := Def.uncached {
      // sbt 2.0 classpaths are virtual-file refs; resolve to real paths via the file converter.
      val converter = fileConverter.value
      val cp = (Runtime / fullClasspath).value
        .map(af => converter.toPath(af.data).toAbsolutePath.toString)
        .mkString(java.io.File.pathSeparator)
      val mainClass = "com.github.mercurievv.scalasemantic.mcpServer"
      val script = target.value / "scalasemantic-mcp"
      val body =
        s"""|#!/usr/bin/env sh
            |# DEV launcher for the ScalaSemantic server. Arg 1 = SemanticDB root (default ".").
            |exec java -cp "$cp" $mainClass "$$@"
            |""".stripMargin
      IO.write(script, body)
      script.setExecutable(true)
      streams.value.log.info(s"MCP dev launcher written: $script")
      script
    },
    mcpClientConfig := {
      // Point at the STABLE installed launcher (scripts/install.sh → ~/.local/bin), which survives
      // `clean` and is independent of the clone location — not the ephemeral target/ dev launcher.
      val launcher =
        java.lang.System.getProperty("user.home") + "/.local/bin/scalasemantic-mcp"
      val root = (ThisBuild / baseDirectory).value.getAbsolutePath
      val json =
        s"""|{
            |  "mcpServers": {
            |    "scala-semantic": {
            |      "command": "$launcher",
            |      "args": ["$root"]
            |    }
            |  }
            |}""".stripMargin
      streams.value.log.info(
        s"Register this in your MCP client (.mcp.json). First run `scripts/install.sh` " +
          s"(or set command to the dev launcher from `mcpLauncher` for in-repo testing):\n$json"
      )
    }
  )

// sbt plugin that host projects add to enable SemanticDB + emit their MCP launch config. It shells
// out to the server process, so it never links against the Scala 3.8.4 server. Not aggregated into
// `root` (it builds against the sbt plugin Scala/version, separate from the modules above); build it
// with `sbtPlugin/compile` or `sbtPlugin/publishLocal`.
lazy val sbtPlugin = (project in file("sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-scalasemantic-mcp",
    // Bundle the auto-download launcher scripts into the plugin jar so `mcpInstall` can write them
    // into a host project's target dir. Single source of truth = top-level scripts/.
    Compile / resourceGenerators += Def.task {
      val root = (ThisBuild / baseDirectory).value
      val outDir = (Compile / resourceManaged).value / "scalasemantic"
      Seq("scalasemantic-mcp.sh", "scalasemantic-mcp.ps1").map { n =>
        val out = outDir / n
        IO.copyFile(root / "scripts" / n, out)
        out
      }
    }.taskValue
  )

// compat-fixtures: tiny source set cross-compiled across Scala versions to emit real SemanticDB for
// the analyzer's cross-version test (CompatSuite). Not part of the published product, not aggregated
// into root. `compatGolden` copies the emitted *.semanticdb into versioned golden resources under
// analysis/src/test/resources so the suite can read them without a cross-compile at test time.
// Scala versions the analyzer is cross-checked against. Add a version here and rerun `compatGoldenAll`
// — nothing else needs editing (the alias loops this list, CompatSuite scans the golden dirs).
// 2.x line + an older 3.x LTS minor; the current 3.8.4 line is already exercised by the dogfooding
// AnalyzerSuite, so it need not be duplicated here (and would collide on the scalaBinaryVersion "3"
// golden dir with 3.3.4).
lazy val compatScalaVersions = Seq("2.13.16", "3.3.4")

lazy val compatFixtures = (project in file("compat-fixtures"))
  .disablePlugins(wartremover.WartRemover)
  .settings(
    name := "scalasemantic-compat-fixtures",
    publish / skip := true,
    crossScalaVersions := compatScalaVersions,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    compatGolden := Def.uncached {
      (Compile / compile).value // ensure SemanticDB is freshly emitted before copying
      val src = (Compile / semanticdbTargetRoot).value / "META-INF" / "semanticdb"
      val dst =
        (ThisBuild / baseDirectory).value / "analysis" / "src" / "test" / "resources" /
          "compat" / s"scala-${scalaBinaryVersion.value}"
      IO.delete(dst)
      if (src.exists()) IO.copyDirectory(src, dst)
      streams.value.log.info(s"compat golden: $src -> $dst")
    }
  )

lazy val compatGolden = taskKey[Unit]("Copy emitted SemanticDB into versioned golden test resources")

// Regenerate the golden SemanticDB for every compat Scala version in one shot. CI runs this and fails
// if the committed golden files drift, keeping the cross-version fixtures honest as compilers bump.
addCommandAlias(
  "compatGoldenAll",
  compatScalaVersions.map(v => s"++$v compatFixtures/compatGolden").mkString("; ", "; ", "")
)

// docs: mdoc-powered documentation site. The sbt-mdoc *plugin* has no sbt 2.0 build yet, so we use
// the mdoc *library* directly via a tiny wrapper main (DocsMain): it compiles + runs the Scala
// snippets in `docs/mdoc/*.md` against the analyzer's own classpath, writing rendered Markdown into
// `website/docs/` for the Docusaurus microsite. `sbt docs/run` regenerates; snippets that touch
// SemanticDB need a prior `compile` (this build emits its own). Not aggregated/published.
// mdoc-powered documentation site, driven through the mdoc *library* (the sbt-mdoc plugin has no
// sbt 2.0 build) via DocsMain. Pinned to a Scala 3 LTS that mdoc supports and kept STANDALONE: mdoc
// 2.9.0's snippet compiler cannot read the main build's bleeding-edge 3.8.4 TASTy, so it can neither
// run on 3.8.4 nor `dependsOn` the analyzer. Site snippets are therefore illustrative Scala +
// protocol JSON, not in-process analyzer calls. (Switch to live snippets once mdoc supports 3.8.x.)
lazy val docs = (project in file("mdoc-docs"))
  .disablePlugins(wartremover.WartRemover)
  .settings(
    name := "scalasemantic-docs",
    publish / skip := true,
    scalaVersion := "3.3.4",
    conflictWarning := ConflictWarning.disable,
    // Fork from the repo root so DocsMain's relative in/out paths (`docs/mdoc` -> `website/docs`)
    // resolve correctly.
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
    libraryDependencies += "org.scalameta" %% "mdoc" % "2.9.0"
  )

lazy val root = (project in file("."))
  .aggregate(core, analysis, mcp)
  .settings(name := "ScalaSemantic", publish / skip := true)

// Pre-push gate. A command alias (not a task) so clean/format/fix/test aggregate across all
// modules. `testOnly *` forces the full suite (sbt 2.0 `test` is cached testQuick — see docs/PLAN.md).
addCommandAlias("prePush", "clean; scalafmtAll; scalafixAll; Test/testOnly *")

