package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.Launcher
import com.github.mercurievv.scalasemantic.LauncherDoctor
import com.github.mercurievv.scalasemantic.LauncherGuardHook

import java.nio.file.Files
import java.nio.file.Path

/** `scalasemantic-mcp doctor`: the self-test that says out loud when the guard hook is installed
  * but would let every text tool through anyway.
  */
class DoctorSuite extends munit.FunSuite:

  private def runSetup(root: Path, extraArgs: String*): Unit =
    Launcher.run(
      Seq("setup", "--project", root.toString, "--client", "claude", "--skip-semanticdb-config") ++
        extraArgs
    )(_ => fail("setup must not start the MCP server"))

  private def tempProject(name: String): Path =
    val root = Files.createTempDirectory(name).nn
    root.toFile.nn.deleteOnExit()
    root

  private def emitSemanticdb(root: Path, relDir: String): Unit =
    val dir = root.resolve(relDir)
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("Fixture.scala.semanticdb"), "")

  private def check(report: LauncherDoctor.Report, label: String): LauncherDoctor.Check =
    report.checks
      .find(_.label == label)
      .getOrElse(fail(s"no check named '$label' in:\n${report.lines.mkString("\n")}"))

  test("a fresh install without a compiled project is reported as failing open") {
    val root = tempProject("ss-doctor-no-index")
    runSetup(root)

    val report = LauncherDoctor.inspect(root)
    assert(report.installed, report.lines.mkString("\n"))
    assert(
      report.failsOpen,
      s"an install with no index must be flagged:\n${report.lines.mkString("\n")}"
    )
    assert(!check(report, "SemanticDB index").ok)
    assert(
      report.failures.exists(_.contains("compile the project")),
      s"the failure must say how to fix it:\n${report.failures.mkString("\n")}"
    )
  }

  private def hasJsonReader: Boolean =
    Seq("jq", "python3").exists(cmd =>
      scala.util
        .Try(
          scala.sys.process
            .Process(Seq("sh", "-c", s"command -v $cmd"))
            .!(scala.sys.process.ProcessLogger(_ => (), _ => ())) == 0
        )
        .getOrElse(false)
    )

  test("a compiled project with the guard installed is healthy") {
    assume(hasJsonReader, "needs jq or python3")
    val root = tempProject("ss-doctor-healthy")
    runSetup(root)
    emitSemanticdb(root, "out/core/semanticDbData.dest/classes/META-INF/semanticdb/com/example")

    val report = LauncherDoctor.inspect(root)
    assert(
      !report.failsOpen,
      s"a compiled, configured project must be clean:\n${report.lines.mkString("\n")}"
    )
    assertEquals(report.failures, Vector.empty)
  }

  test("--no-guard is not a failure: nothing is installed, so nothing fails open") {
    val root = tempProject("ss-doctor-optout")
    runSetup(root, "--no-guard")

    val report = LauncherDoctor.inspect(root)
    assert(!report.installed)
    assert(!report.failsOpen, report.lines.mkString("\n"))
  }

  test("a hook body from an older release is reported as stale") {
    val root = tempProject("ss-doctor-stale")
    runSetup(root)
    emitSemanticdb(root, "out/core/semanticDbData.dest/classes/META-INF/semanticdb")
    val hook = root.resolve(LauncherGuardHook.HookRelPath)
    // The pre-fix probe: it pruned `out`/`target`, so it never found the index it was looking for.
    Files.writeString(
      hook,
      Files
        .readString(hook)
        .replace(
          "-path '*/META-INF/semanticdb/*.semanticdb' -print",
          "-name '*.semanticdb' -print"
        )
    )

    val report = LauncherDoctor.inspect(root)
    assert(!check(report, "guard hook up to date").ok, report.lines.mkString("\n"))
    assert(report.failsOpen, "a stale hook body must be flagged, not silently trusted")
  }

  test("an unregistered hook is reported: the script exists but Claude Code never runs it") {
    val root = tempProject("ss-doctor-unregistered")
    runSetup(root)
    emitSemanticdb(root, "out/core/semanticDbData.dest/classes/META-INF/semanticdb")
    Files.writeString(root.resolve(".claude/settings.json"), "{}\n")

    val report = LauncherDoctor.inspect(root)
    assert(!check(report, "guard hook registered").ok, report.lines.mkString("\n"))
    assert(report.failsOpen)
  }

  // --- the probe itself ----------------------------------------------------------------------

  test("the index probe finds what every mainstream build tool actually emits") {
    val layouts = Seq(
      "out/core/semanticDbData.dest/classes/META-INF/semanticdb",
      "out/mcp/semanticDbData.super/classes/META-INF/semanticdb/com/example/deep",
      "target/scala-3.8.4/classes/META-INF/semanticdb/src/main/scala",
      ".scala-build/project_abc/classes/main/META-INF/semanticdb"
    )
    layouts.foreach { rel =>
      val root = tempProject("ss-doctor-probe")
      emitSemanticdb(root, rel)
      assert(
        LauncherDoctor.semanticdbIndex(root).isDefined,
        s"an index at $rel must be found — that is where the build puts it"
      )
    }
  }

  test("the index probe ignores semanticdb files in trees the guard prunes") {
    val root = tempProject("ss-doctor-probe-pruned")
    emitSemanticdb(root, ".worktrees/feature/out/core/classes/META-INF/semanticdb")
    emitSemanticdb(root, "node_modules/pkg/META-INF/semanticdb")
    assertEquals(LauncherDoctor.semanticdbIndex(root), None)
  }

  test("a .semanticdb outside a META-INF/semanticdb directory does not count") {
    val root = tempProject("ss-doctor-probe-stray")
    val stray = root.resolve("docs")
    Files.createDirectories(stray)
    Files.writeString(stray.resolve("Sample.scala.semanticdb"), "")
    assertEquals(LauncherDoctor.semanticdbIndex(root), None)
  }
