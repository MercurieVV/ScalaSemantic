import com.github.sbt.git.SbtGit.GitKeys.*

ThisBuild / scalaVersion := "3.8.4"
useReadableConsoleGit

Global / excludeLintKeys ++= Set(
  ThisBuild / gitUncommittedChanges,
  gitDescribedVersion
)

ThisBuild / scalafixDependencies += "org.typelevel" %% "typelevel-scalafix" % "0.5.0"

// --- Publishing (Sonatype Central via sbt-ci-release) ----------------------------------------
// Version is derived from git tags by sbt-dynver: a `vX.Y.Z` tag publishes `X.Y.Z`. The Central
// namespace for a GitHub account is io.github.<user>. `ci-release` signs + uploads on tag pushes.
ThisBuild / organization := "io.github.mercurievv"
ThisBuild / organizationName := "mercurievv"
ThisBuild / homepage := Some(url("https://github.com/mercurievv/ScalaSemantic"))
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / developers := List(
  Developer(
    "mercurievv",
    "Viktor Skalinins",
    "mercurievv@gmail.com",
    url("https://github.com/mercurievv")
  )
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

lazy val strictCompileWarts = Seq(
  Wart.ArrayEquals,
  Wart.ArrayToString,
  Wart.EitherProjectionPartial,
  Wart.Enumeration,
  Wart.IterableOps,
  Wart.JavaNetURLConstructors,
  Wart.LeakingSealed,
  Wart.ListAppend,
  Wart.MapUnit,
  Wart.ObjectThrowable,
  Wart.OptionPartial,
  Wart.PartialFunctionApply,
  Wart.SeqApply,
  Wart.StringPlusAny,
  Wart.TripleQuestionMark,
  Wart.TryPartial,
  Wart.While
)
lazy val strictCompileWartNames = Set(
  "ArrayEquals",
  "ArrayToString",
  "EitherProjectionPartial",
  "Enumeration",
  "IterableOps",
  "JavaNetURLConstructors",
  "LeakingSealed",
  "ListAppend",
  "MapUnit",
  "ObjectThrowable",
  "OptionPartial",
  "PartialFunctionApply",
  "SeqApply",
  "StringPlusAny",
  "TripleQuestionMark",
  "TryPartial",
  "While"
)

// Shared across all modules: SemanticDB emission (for dogfooding + scalafix) and wart rules.
lazy val commonSettings = Seq(
  // Required by OrganizeImports scalafix rule. Scala 2.12 uses the old spelling.
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 12)) =>
        Seq(
          "-Xfatal-warnings",
          "-Ywarn-unused:imports",
          "-Ywarn-unused:locals",
          "-Ywarn-unused:patvars",
          "-Ywarn-unused:privates"
        )
      case _ => Seq("-Werror", "-Wunused:all")
    }
  } :+ "-Wconf:msg=.*unused.*:e",
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
  ),
  Compile / wartremoverErrors ++= strictCompileWarts,
  Test / wartremoverErrors --= strictCompileWarts,
  Test / scalacOptions := (Test / scalacOptions).value.filterNot { opt =>
    opt.startsWith("-P:wartremover:traverser:") &&
    strictCompileWartNames.exists(name => opt.endsWith(s".$name"))
  }
)

lazy val munit = "org.scalameta" %% "munit" % "1.2.4" % Test
lazy val munitScalacheck = "org.scalameta" %% "munit-scalacheck" % "1.3.0" % Test
lazy val upickle = "com.lihaoyi" %% "upickle" % "4.4.3"
lazy val refined = "eu.timepit" %% "refined" % "0.11.3"

// Generate a standalone, build-tool-agnostic launcher for the MCP server: a script that runs the
// server on a clean JVM (no sbt → no stdout pollution of the JSON-RPC stream).
lazy val mcpLauncher = taskKey[File]("Write a standalone MCP server launch script")
lazy val mcpClientConfig =
  inputKey[Unit](
    "Install the launcher + write client config pointing at it (jar auto-updates in bg)"
  )
lazy val proguard = taskKey[File]("Run ProGuard to shrink the assembly JAR")
lazy val testShrunk = taskKey[Unit]("Run tests using the shrunk ProGuard JAR")

// core: load + index SemanticDB and expose symbol-grammar primitives. No JSON, no MCP.
// scalameta + its SemanticDB bindings are now published natively for Scala 3 (semanticdb-shared_3
// from 4.16.2), so we depend on the _3 artifacts directly — no more for3Use2_13. This keeps the
// whole transitive ScalaPB/protobuf stack on the Scala 3 line. `semanticdb-shared` carries the
// scala.meta.internal.semanticdb.* protobuf classes (not pulled in by `scalameta`).
lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-core",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % "4.17.0",
      "org.scalameta" %% "semanticdb-shared" % "4.17.0",
      munit
    )
  )

// pc: presentation-compiler backend. Wraps Scala 3's own in-tree PC (moved into the distribution
// at 3.4+; the old per-patch `mtags_<full>` artifact is no longer published) to (re)generate
// SemanticDB for an edited/uncompiled/broken buffer IN MEMORY — error-tolerant, unlike the disk
// SemanticDB which only appears after a clean compile. `scala3-presentation-compiler_3` implements
// the stable `scala.meta.pc` (mtags-interfaces) API and is version-locked to the compiler. Kept in
// its own module so the heavy compiler dep stays out of `core`. WartRemover off: Java interop.
lazy val pc = (project in file("pc"))
  .dependsOn(core)
  .disablePlugins(wartremover.WartRemover)
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-pc",
    // Fork tests so the forked JVM's `java.class.path` IS the module's real test classpath
    // (scala-library, the compiler, deps). Unforked, `java.class.path` is only sbt's launcher, so
    // the PC the test spins up would have no scala-library to typecheck against. This also mirrors
    // production: the MCP server is its own JVM whose classpath is what the PC must analyse against.
    Test / fork := true,
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-presentation-compiler" % "3.8.4",
      munit
    )
  )

// analysis: the query engine + result models (upickle), built on core, with the PC backend as a
// second (in-memory, error-tolerant) source of SemanticDB for the position-local tools.
//
// Stainless library: `stainless-library_3` is NOT published to Maven Central, so we DON'T depend on
// it as a managed artifact (that would fail to resolve in a clean CI checkout). Instead the v0.9.9.3
// release jar is checked in at `analysis/lib/stainless-library.jar` and picked up as an UNMANAGED
// dependency (sbt auto-includes jars under a module's `lib/`). It supplies @pure/@opaque and
// stainless.lang for the contracts. It IS bundled into the mcp fat jar (see the assembly settings
// below): `PureKernels` imports `stainless.lang.*` for the IEEE-754 `Double` model its NaN guard
// needs, leaving a runtime reference; under that import `require`/`ensuring` are erased ghosts, so
// the bundled jar costs classpath presence but no per-call overhead. The sbt-stainless plugin from
// the same release is sbt-1.x-only and can't load under sbt 2.0, so verification runs via the
// standalone tool (`sbt stainlessVerify`), not at compile time.

// Formal-verification gate: run the standalone Stainless tool over PureKernels.scala (the
// production numeric/geometric kernels, verified in place — no mirror) via
// scripts/stainless-verify.sh (which downloads + caches the tool, bundled solvers included). The
// script fails iff a VC is INVALID, tolerating the `unknown`/timeout VCs the bundled smt-z3 cannot
// discharge. CI runs `sbt stainlessVerify`; devs can too.
lazy val stainlessVerify =
  taskKey[Unit]("Formally verify PureKernels.scala with the standalone Stainless tool")

lazy val analysis = (project in file("analysis"))
  .dependsOn(core, pc)
  .settings(commonSettings)
  .settings(
    name := "scalasemantic-analysis",
    // stainless-library is supplied as an unmanaged jar from analysis/lib/ (see note above), so it
    // is NOT listed here as a managed dependency.
    libraryDependencies ++= Seq(upickle, refined, munit, munitScalacheck),
    stainlessVerify := {
      val log = streams.value.log
      val root = (ThisBuild / baseDirectory).value
      val script = root / "scripts" / "stainless-verify.sh"
      log.info(s"Running $script ...")
      val rc = scala.sys.process.Process(Seq("bash", script.getAbsolutePath), root).!
      if (rc != 0) sys.error(s"stainlessVerify failed (exit $rc) — see output above")
    }
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
    // stainless-library reaches this classpath as an unmanaged jar from analysis/lib/. It IS bundled
    // into the fat jar: `PureKernels` imports `stainless.lang.*` so its `pageRankBase` contract can
    // use stainless's IEEE-754 `Double` model (`.isNaN`) — the only not-NaN witness the verifier
    // accepts — which leaves a runtime reference to the stainless namespace. Under the import,
    // `require`/`ensuring` become stainless's erased ghost variants (zero runtime cost), so shipping
    // the jar adds the dependency on the classpath without adding per-call checking overhead.
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*)
          if xs.lastOption
            .exists(s => s.endsWith(".SF") || s.endsWith(".DSA") || s.endsWith(".RSA")) =>
        MergeStrategy.discard
      case x if x.endsWith("module-info.class") => MergeStrategy.discard
      // The `pc` backend pulls scala3-presentation-compiler → the 2.13 compiler, whose bundled
      // `scala/tools/asm/*` collides with scala3's `scala-asm` jar. Same classes, different jars —
      // keep one copy.
      case PathList("scala", "tools", "asm", _*) => MergeStrategy.first
      // Version/marker .properties duplicated across compiler/reflect/compat/coursier jars.
      case x if x.endsWith(".properties") => MergeStrategy.first
      case x                              => (assembly / assemblyMergeStrategy).value.apply(x)
    },
    // --- ProGuard Custom Task ---
    proguard := Def.uncached {
      val log = streams.value.log
      val inputJar = (assembly / assemblyOutputPath).value
      val outputJar = target.value / "proguard" / "scalasemantic-mcp-shrunk.jar"

      IO.delete(outputJar)
      IO.createDirectory(outputJar.getParentFile)
      log.info(s"Running ProGuard on $inputJar...")

      val args = Array(
        "-injars",
        inputJar.getAbsolutePath,
        "-outjars",
        outputJar.getAbsolutePath,
        "-libraryjars",
        s"${System.getProperty("java.home")}/jmods/java.base.jmod(!**.jar;!module-info.class)",
        "-dontobfuscate",
        "-dontoptimize",
        "-ignorewarnings",
        "-dontnote",
        "-dontwarn",
        "-keepattributes",
        "Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod",
        "-keep",
        "public class com.github.mercurievv.scalasemantic.mcpServer { public static void main(java.lang.String[]); }",
        "-keep",
        "class com.github.mercurievv.scalasemantic.mcpServer$ { *; }",
        "-keep",
        "class com.github.mercurievv.scalasemantic.** { *; }",
        "-keepclassmembers",
        "class * { public static ** MODULE$; }",
        "-keep",
        "class scala.Dynamic { *; }",
        "-keep",
        "class * extends upickle.core.Reader { *; }",
        "-keep",
        "class * extends upickle.core.Writer { *; }",
        "-keepclassmembers",
        "class scala.** { *; }",
        "-keepclassmembers",
        "class dotty.tools.** { *; }",
        "-keepclassmembers",
        "class org.scalameta.** { *; }",
        "-keep",
        "class scala.meta.internal.metals.** { *; }",
        "-keep",
        "class dotty.tools.pc.** { *; }",
        "-keepclassmembers",
        "class upickle.** { *; }",
        "-keepclassmembers",
        "class ujson.** { *; }",
        "-keepclassmembers",
        "class upack.** { *; }",
        "-keepclassmembers",
        "class com.google.protobuf.** { *; }",
        "-keepclassmembers",
        "class fastparse.** { *; }",
        "-keepclassmembers",
        "class geny.** { *; }",
        "-keepclassmembers",
        "class sourcecode.** { *; }",
        "-keep",
        "class xsbti.** { *; }",
        "-keep",
        "class org.eclipse.lsp4j.** { *; }"
      )

      try {
        val configuration = new _root_.proguard.Configuration()
        val parser = new _root_.proguard.ConfigurationParser(args, java.lang.System.getProperties)
        parser.parse(configuration)
        new _root_.proguard.ProGuard(configuration).execute()
        log.info(s"ProGuard completed successfully. Shrunk JAR: $outputJar")
      } catch {
        case e: Throwable =>
          log.error(s"ProGuard execution failed: ${e.getMessage}")
          throw e
      }
      outputJar
    },
    // --- Run Tests on Shrunk JAR Custom Task ---
    testShrunk := Def.uncached {
      val log = streams.value.log
      val converter = fileConverter.value

      // Get the compile classpath of the mcp project to know what was packaged in the fat jar
      val compileCp = (Compile / fullClasspath).value
        .map(af => converter.toPath(af.data).toAbsolutePath.toFile.getCanonicalPath)
        .toSet

      // Combine test classpaths from pc and mcp modules (to include all tests)
      val pcTestCp = (pc / Test / fullClasspath).value
        .map(af => converter.toPath(af.data).toAbsolutePath.toFile.getCanonicalPath)

      val mcpTestCp = (Test / fullClasspath).value
        .map(af => converter.toPath(af.data).toAbsolutePath.toFile.getCanonicalPath)

      val testCp = (pcTestCp ++ mcpTestCp).distinct

      // The shrunk jar generated by our proguard task
      val shrunkJar = proguard.value.getCanonicalPath

      // Remove all compile-classpath dependencies (they are inside the shrunk jar)
      // and add the shrunk JAR instead. Keep test dependencies and test-classes.
      val filteredTestCp = testCp.filterNot(compileCp.contains) :+ shrunkJar

      log.info(s"Running tests on the shrunk ProGuard JAR ($shrunkJar)...")

      val testSuites = Seq(
        "com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackendSuite",
        "com.github.mercurievv.scalasemantic.analysis.AnalyzerPcSuite",
        "com.github.mercurievv.scalasemantic.mcp.McpSuite"
      )

      val cpString = filteredTestCp.mkString(java.io.File.pathSeparator)
      val forkOptions = ForkOptions()
        .withOutputStrategy(Some(StdoutOutput))
        .withConnectInput(false)

      val runArgs = Seq("-cp", cpString, "org.junit.runner.JUnitCore") ++ testSuites
      val exitCode = Fork.java(forkOptions, runArgs)
      if (exitCode != 0) {
        sys.error(s"Tests failed with exit code $exitCode")
      }
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
      val args = sbt.complete.DefaultParsers.spaceDelimited("<client>").parsed
      val log = streams.value.log
      val home = file(java.lang.System.getProperty("user.home"))
      val launcher = home / ".local" / "bin" / "scalasemantic-mcp.sh"
      val root = (ThisBuild / baseDirectory).value
      IO.copyFile(root / "scripts" / "scalasemantic-mcp.sh", launcher)
      val _ = launcher.setExecutable(true)
      log.info(s"MCP launcher installed: $launcher")
      try {
        val rc = scala.sys.process.Process(Seq(launcher.getAbsolutePath, "--prefetch", ".")).!
        if (rc != 0)
          log.warn(s"jar prefetch returned $rc; it will download on first connect instead.")
      } catch {
        case scala.util.control.NonFatal(e) =>
          log.warn(s"jar prefetch skipped (${e.getMessage}); it will download on first connect.")
      }

      val cpFile = launcher.getParentFile / "scala-semantic-classpath.txt"
      val argv = Seq(
        launcher.getAbsolutePath,
        root.getAbsolutePath,
        cpFile.getAbsolutePath,
        "--log",
        "--log-output"
      )
      val serverName = "scala-semantic"
      val clientVal = args.headOption.getOrElse("claude")

      val clients = if (clientVal.trim.toLowerCase == "all") {
        Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity")
      } else {
        Seq(clientVal)
      }

      import com.github.mercurievv.scalasemantic.sbtplugin.ScalaSemanticConfigMerger
      for (client <- clients) {
        val target = ScalaSemanticConfigMerger.targetFor(client)
        val outFile = root / target.relPath
        val existing = if (outFile.exists) Some(IO.read(outFile)) else None
        val merged = target.fmt match {
          case ScalaSemanticConfigMerger.JsonFmt =>
            ScalaSemanticConfigMerger.mergeJson(existing, serverName, argv, target.extraJson)
          case ScalaSemanticConfigMerger.TomlFmt =>
            ScalaSemanticConfigMerger.mergeToml(existing, serverName, argv)
          case ScalaSemanticConfigMerger.YamlFmt =>
            ScalaSemanticConfigMerger.mergeYaml(existing, serverName, argv)
        }
        IO.write(outFile, merged)
        val verb = if (existing.isEmpty) "Wrote" else "Merged into"
        log.info(s"$verb MCP config for server '$serverName': $outFile")
        ScalaSemanticConfigMerger.writeRulesAndSteer(client, root, log)
      }
    }
  )

// sbt plugin that host projects add to enable SemanticDB + emit their MCP launch config. It shells
// out to the server process, so it never links against the Scala 3.8.4 server. Cross-build it as a
// real sbt plugin: Scala 2.12 for sbt 1.x, Scala 3 for sbt 2.x. Not aggregated into `root` (it
// builds against the sbt plugin Scala/version, separate from the modules above); build it with
// `+sbtPlugin/compile` or `+sbtPlugin/publishLocal`.
lazy val sbtPlugin = (project in file("sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .settings(commonSettings)
  .settings(
    name := "sbt-scalasemantic-mcp",
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "project" / "ScalaSemanticConfigMerger.scala",
    // Hard-code both axes so sbt 2.0.1's SbtPlugin rewrite of scalaVersion (to 2.13.x) does not
    // pollute the cross-build matrix and produce an unresolvable scripted-sbt_2.13:2.0.x request.
    crossScalaVersions := Seq("2.12.21", "3.8.4"),
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" | "2.13" => "1.11.6"
        case _               => "2.0.1"
      }
    },
    // munit unit-tests the pure config-merge helpers (no sbt/scripted machinery needed). munit 1.2.3
    // is published for both the 2.12 and 3 plugin axes, so `%%` resolves on each cross-build.
    libraryDependencies += munit,
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

lazy val compatGolden =
  taskKey[Unit]("Copy emitted SemanticDB into versioned golden test resources")

// Regenerate the golden SemanticDB for every compat Scala version in one shot. CI runs this and fails
// if the committed golden files drift, keeping the cross-version fixtures honest as compilers bump.
addCommandAlias(
  "compatGoldenAll",
  compatScalaVersions.map(v => s"++$v compatFixtures/compatGolden").mkString("; ", "; ", "")
)

// docs: mdoc-powered documentation site. The sbt-mdoc *plugin* has no sbt 2.0 build yet, so we use
// the mdoc *library* directly via a tiny wrapper main (DocsMain): it compiles + runs the Scala
// snippets in `docs/**/*.md` against the analyzer's own classpath, writing rendered Markdown into
// `website/docs/` for the Docusaurus microsite. `sbt docs/run` regenerates; snippets that touch
// SemanticDB need a prior `compile` (this build emits its own). Not aggregated/published.
// mdoc-powered documentation site, driven through the mdoc *library* (the sbt-mdoc plugin has no
// sbt 2.0 build) via DocsMain. Pinned to a Scala 3 LTS that mdoc supports and kept STANDALONE: mdoc
// 2.9.0's snippet compiler cannot read the main build's bleeding-edge 3.8.4 TASTy, so it can neither
// run on 3.8.4 nor `dependsOn` the analyzer. Site snippets are therefore illustrative Scala +
// protocol JSON, not in-process analyzer calls. (Switch to live snippets once mdoc supports 3.8.x.)
// Latest published release = highest `v*` git tag (strip the `v`). Feeds the docs `@VERSION@` site
// variable so version snippets are filled at site-build time instead of hand-bumped. Best-effort:
// if there are no tags or git is unavailable (e.g. a shallow CI checkout without tags), fall back to
// the `x.y.z` placeholder so the build never fails on this.
lazy val latestReleaseVersion: String =
  try {
    scala.sys.process
      .Process(Seq("git", "tag", "--list", "v*"))
      .!!
      .linesIterator
      .map(_.trim.stripPrefix("v"))
      .filter(_.matches("""\d+\.\d+\.\d+"""))
      .toSeq
      .sortBy { v =>
        val p = v.split('.').map(_.toInt)
        (p(0), p(1), p(2))
      }
      .lastOption
      .getOrElse("x.y.z")
  } catch {
    case _: Throwable => "x.y.z"
  }

lazy val docs = (project in file("mdoc-docs"))
  .disablePlugins(wartremover.WartRemover)
  .settings(
    name := "scalasemantic-docs",
    publish / skip := true,
    scalaVersion := "3.3.4",
    conflictWarning := ConflictWarning.disable,
    // Fork from the repo root so DocsMain's relative in/out paths (`docs` -> `website/docs`)
    // resolve correctly.
    Compile / run / fork := true,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
    // Pass the latest release version to the forked DocsMain, which registers it as the mdoc
    // `@VERSION@` site variable.
    Compile / run / javaOptions += s"-Dscalasemantic.docs.version=$latestReleaseVersion",
    libraryDependencies += "org.scalameta" %% "mdoc" % "2.9.0"
  )

lazy val root = (project in file("."))
  .aggregate(core, pc, analysis, mcp, sbtPlugin)
  .settings(name := "ScalaSemantic", publish / skip := true)

// Pre-push gate. A command alias (not a task) so the steps aggregate across all modules. This is a
// VERIFY-ONLY gate (mirrors CI): scalafmtCheckAll + scalafixAll --check FAIL the push on drift rather
// than silently rewriting files (a pre-push rewrite lands post-commit and never gets pushed — CI then
// rejects it). Formatting is applied earlier by the pre-commit hook; here we only confirm it stuck.
// `testOnly *` forces the full suite (sbt 2.0 `test` is cached testQuick — see docs/research/plan.md).
addCommandAlias(
  "prePush",
  "clean; scalafmtCheckAll; scalafixAll --check; Test/testOnly *; stainlessVerify"
)
