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
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-mcp",
    libraryDependencies ++= Seq(upickle, munit),
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
            |# Standalone launcher for the ScalaSemantic server. Arg 1 = SemanticDB root (default ".").
            |exec java -cp "$cp" $mainClass "$$@"
            |""".stripMargin
      IO.write(script, body)
      script.setExecutable(true)
      streams.value.log.info(s"MCP launcher written: $script")
      script
    },
    mcpClientConfig := {
      val launcher = mcpLauncher.value.getAbsolutePath
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
      streams.value.log.info(s"Register this in your MCP client (e.g. .mcp.json):\n$json")
    }
  )

// sbt plugin that host projects add to enable SemanticDB + emit their MCP launch config. It shells
// out to the server process, so it never links against the Scala 3.8.4 server. Not aggregated into
// `root` (it builds against the sbt plugin Scala/version, separate from the modules above); build it
// with `sbtPlugin/compile` or `sbtPlugin/publishLocal`.
lazy val sbtPlugin = (project in file("sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-scalasemantic-mcp"
  )

lazy val root = (project in file("."))
  .aggregate(core, analysis, mcp)
  .settings(name := "ScalaSemantic", publish / skip := true)

// Pre-push gate. A command alias (not a task) so clean/format/fix/test aggregate across all
// modules. `testOnly *` forces the full suite (sbt 2.0 `test` is cached testQuick — see PLAN.md).
addCommandAlias("prePush", "clean; scalafmtAll; scalafixAll; Test/testOnly *")

