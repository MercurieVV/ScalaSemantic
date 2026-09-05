package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.GuardHookAction
import com.github.mercurievv.scalasemantic.Launcher
import com.github.mercurievv.scalasemantic.LauncherGuardHook
import com.github.mercurievv.scalasemantic.LauncherSetup

import java.nio.file.Files
import java.nio.file.Path

/** The Claude Code `PreToolUse` guard hook installed by `setup`: what it writes, what it refuses to
  * write twice, and — running the generated script for real — which tool calls it actually denies.
  */
class GuardHookSuite extends munit.FunSuite:

  private def runSetup(root: Path, extraArgs: String*): Unit =
    Launcher.run(
      Seq("setup", "--project", root.toString, "--client", "claude", "--skip-semanticdb-config") ++
        extraArgs
    )(_ => fail("setup must not start the MCP server"))

  private def tempProject(name: String): Path =
    val root = Files.createTempDirectory(name).nn
    root.toFile.nn.deleteOnExit()
    root

  test("--rwhook-local installs the guard hook and registers it as a PreToolUse hook") {
    val root = tempProject("ss-guard-install")
    runSetup(root, "--rwhook-local")

    val hook = root.resolve(LauncherGuardHook.HookRelPath)
    assert(Files.exists(hook), s"expected $hook")
    assert(Files.isExecutable(hook), s"$hook must be executable")

    val settings = Files.readString(root.resolve(".claude/settings.json"))
    assert(settings.contains("PreToolUse"), settings)
    assert(settings.contains("scala-semantic-guard.sh"), settings)
    assert(settings.contains(LauncherGuardHook.Matcher), settings)
    assert(
      settings.contains("Edit") && settings.contains("Write"),
      s"the guard must be registered for edit tools too, or its edit branch never runs:\n$settings"
    )
  }

  // Installing the hook changes how every later session in that directory reads Scala, so it is
  // opt-in: a plain `setup` (or the legacy --no-guard) must leave the directory hook-free.
  test("no guard hook is installed unless one is asked for") {
    Seq(Seq.empty[String], Seq("--no-guard")).foreach { flags =>
      val root = tempProject("ss-guard-optout")
      runSetup(root, flags*)

      assert(!Files.exists(root.resolve(LauncherGuardHook.HookRelPath)), flags.toString)
      assert(!Files.exists(root.resolve(".claude/settings.json")), flags.toString)
    }
  }

  // ...but an install that IS there must keep being upgraded by a plain setup run, or a guard
  // written by an older launcher silently stays behind forever.
  test("a plain setup run regenerates a guard hook that is already installed") {
    val root = tempProject("ss-guard-refresh")
    runSetup(root, "--rwhook-local")
    val hook = root.resolve(LauncherGuardHook.HookRelPath)
    Files.writeString(hook, "#!/bin/sh\n# scala-semantic-guard from an older release\nexit 0\n")

    runSetup(root)

    assertEquals(Files.readString(hook), LauncherGuardHook.script(strictEdits = false))
  }

  test("re-running setup neither duplicates nor rewrites the registration") {
    val root = tempProject("ss-guard-idempotent")
    runSetup(root, "--rwhook-local")
    val first = Files.readString(root.resolve(".claude/settings.json"))
    runSetup(root, "--rwhook-local")
    val second = Files.readString(root.resolve(".claude/settings.json"))

    assertEquals(second, first)
    assertEquals("scala-semantic-guard".r.findAllIn(second).size, 1)
  }

  test("registration splices into existing settings without dropping what is there") {
    val existing =
      """|{
         |  "model": "opus",
         |  "hooks": {
         |    "PostToolUse": [
         |      { "matcher": "Bash", "hooks": [{ "type": "command", "command": "log.sh" }] }
         |    ]
         |  }
         |}
         |""".stripMargin

    val merged = LauncherGuardHook
      .mergeSettings(Some(existing))
      .getOrElse(fail("expected the guard entry to be added"))

    assert(merged.contains("\"model\": \"opus\""), merged)
    assert(merged.contains("PostToolUse"), merged)
    assert(merged.contains("log.sh"), merged)
    assert(merged.contains("PreToolUse"), merged)
    assert(merged.contains("scala-semantic-guard.sh"), merged)
    assertEquals(LauncherGuardHook.mergeSettings(Some(merged)), None)
  }

  test("registration appends to an existing PreToolUse array") {
    val existing =
      """|{
         |  "hooks": {
         |    "PreToolUse": [
         |      { "matcher": "Bash", "hooks": [{ "type": "command", "command": "other.sh" }] }
         |    ]
         |  }
         |}
         |""".stripMargin

    val merged = LauncherGuardHook
      .mergeSettings(Some(existing))
      .getOrElse(fail("expected the guard entry to be added"))

    assert(merged.contains("other.sh"), merged)
    assert(merged.contains("scala-semantic-guard.sh"), merged)
    assertEquals("PreToolUse".r.findAllIn(merged).size, 1, merged)
  }

  // --- user scope, and removal ---------------------------------------------------------------

  test("--rwhook-user installs into the user's own .claude, addressed by absolute path") {
    val home = tempProject("ss-guard-home")
    LauncherGuardHook.installUser(home, "claude", strictEdits = false)

    val hook = home.resolve(LauncherGuardHook.HookRelPath)
    assert(Files.exists(hook), s"expected $hook")
    assert(Files.isExecutable(hook), s"$hook must be executable")

    val settings = Files.readString(home.resolve(".claude/settings.json"))
    // $CLAUDE_PROJECT_DIR points at the project being edited, which holds no copy of this script.
    assert(!settings.contains("CLAUDE_PROJECT_DIR"), settings)
    assert(settings.contains(hook.toAbsolutePath.normalize().toString), settings)
    assert(settings.contains(LauncherGuardHook.Matcher), settings)
  }

  // `--rwhook-user` and `--rw-hook-remove` end up in $HOME, which a test must not touch: drive the
  // one step that decides where the hook goes, with `home` pointed at a temp directory.
  private def applyHook(project: Path, home: Path, action: GuardHookAction): Unit =
    LauncherSetup.applyGuardHook(
      LauncherSetup.Options(project = project, home = home, client = "claude", guardHook = action)
    )

  test("--rwhook-user puts the hook in the user's config, and leaves the project alone") {
    val project = tempProject("ss-guard-user-scope")
    val home = tempProject("ss-guard-user-home")
    applyHook(project, home, GuardHookAction.User)

    assert(Files.exists(home.resolve(LauncherGuardHook.HookRelPath)))
    assert(!Files.exists(project.resolve(LauncherGuardHook.HookRelPath)))
  }

  test("--rw-hook-remove clears the hook from the project and the user config at once") {
    val project = tempProject("ss-guard-remove-project")
    val home = tempProject("ss-guard-remove-home")
    applyHook(project, home, GuardHookAction.Project)
    applyHook(project, home, GuardHookAction.User)
    assert(Files.exists(project.resolve(LauncherGuardHook.HookRelPath)))
    assert(Files.exists(home.resolve(LauncherGuardHook.HookRelPath)))

    // Which scope an install went into is not something the person uninstalling has to remember.
    applyHook(project, home, GuardHookAction.Remove)

    assert(!Files.exists(project.resolve(LauncherGuardHook.HookRelPath)))
    assert(!Files.exists(home.resolve(LauncherGuardHook.HookRelPath)))
    Seq(project, home).foreach { dir =>
      assert(!Files.exists(dir.resolve(".claude/settings.json")), dir.toString)
    }
  }

  test(
    "uninstall removes the hook and its registration, and says it found nothing when it did not"
  ) {
    val root = tempProject("ss-guard-uninstall")
    runSetup(root, "--rwhook-local")

    assert(LauncherGuardHook.uninstall(root), "uninstall must report what it removed")
    assert(!Files.exists(root.resolve(LauncherGuardHook.HookRelPath)))
    // Nothing this install created is left behind as an empty shell: settings.json held the guard
    // entry and nothing else, so it goes too.
    assert(!Files.exists(root.resolve(".claude/settings.json")))
    assert(!Files.exists(root.resolve(".claude/hooks")))

    assert(!LauncherGuardHook.uninstall(root), "a second uninstall has nothing left to remove")
  }

  test("removal takes the guard entry and nothing else") {
    val installed = LauncherGuardHook
      .mergeSettings(
        Some(
          """|{
             |  "model": "opus",
             |  "hooks": {
             |    "PreToolUse": [
             |      { "matcher": "Bash", "hooks": [{ "type": "command", "command": "other.sh" }] }
             |    ],
             |    "PostToolUse": [
             |      { "matcher": "Bash", "hooks": [{ "type": "command", "command": "log.sh" }] }
             |    ]
             |  }
             |}
             |""".stripMargin
        )
      )
      .getOrElse(fail("expected the guard entry to be added"))

    val removed = LauncherGuardHook
      .removeSettings(installed)
      .getOrElse(fail("expected the guard entry to be removed"))

    assert(!removed.contains("scala-semantic-guard"), removed)
    assert(removed.contains("\"model\": \"opus\""), removed)
    assert(removed.contains("other.sh"), removed)
    assert(removed.contains("log.sh"), removed)
    // The PreToolUse array still has a tenant, so it stays.
    assert(removed.contains("PreToolUse"), removed)
    assertEquals(LauncherGuardHook.removeSettings(removed), None, "nothing left to remove")
    // And what is left is still the same JSON: re-installing over it settles in one pass.
    val reinstalled = LauncherGuardHook
      .mergeSettings(Some(removed))
      .getOrElse(fail("expected the guard entry to be added back"))
    assertEquals(LauncherGuardHook.mergeSettings(Some(reinstalled)), None)
  }

  test("removing the last PreToolUse entry takes the empty scaffolding with it") {
    val installed = LauncherGuardHook
      .mergeSettings(Some("""{ "model": "opus" }"""))
      .getOrElse(fail("expected the guard entry to be added"))

    val removed = LauncherGuardHook
      .removeSettings(installed)
      .getOrElse(fail("expected the guard entry to be removed"))

    assert(!removed.contains("PreToolUse"), removed)
    assert(!removed.contains("hooks"), removed)
    assert(removed.contains("\"model\": \"opus\""), removed)
  }

  test("removeSettings leaves a file that never registered the guard alone") {
    assertEquals(LauncherGuardHook.removeSettings("""{ "model": "opus" }"""), None)
  }

  // --- the standalone installer must ship the same hook ------------------------------------

  // The scala-cli installer (scripts/scalasemantic-mcp.scala) was deleted in ADR-0004: it
  // duplicated the launcher module, so there is no second copy of the guard script left to drift.
  // The PowerShell installer still carries its own copy, hence the check below.

  test("the PowerShell installer ships the same guard script as the jar") {
    val ps1 = Files.readString(Path.of("scripts/scalasemantic-mcp.ps1"))
    assert(
      ps1.contains(LauncherGuardHook.script(strictEdits = false).trim),
      "PowerShell guard body drifted"
    )
    assert(ps1.contains("Install-GuardHook"), "PowerShell installer must call the guard install")
  }

  // --- the generated script, executed ------------------------------------------------------

  private def hasJsonReader: Boolean =
    Seq("jq", "python3").exists(cmd =>
      scala.util
        .Try(scala.sys.process.Process(Seq("sh", "-c", s"command -v $cmd")).!(silentLogger) == 0)
        .getOrElse(false)
    )

  private def silentLogger =
    scala.sys.process.ProcessLogger(_ => (), _ => ())

  /** A project the guard considers "semantic answers are available for": MCP server configured and
    * at least one emitted `*.semanticdb`. Without both the hook deliberately fails open.
    */
  private def guardedProject(name: String): Path =
    val root = tempProject(name)
    runSetup(root, "--rwhook-local")
    emitSemanticdb(root, "out/core/semanticDbData.dest/classes/META-INF/semanticdb")
    root

  /** Where the compiler actually drops the index: under the build tool's own output directory (Mill
    * `out/`, sbt `target/`), never at the project root. A probe that prunes those dirs finds
    * nothing and the guard fails open on every real project.
    */
  private def emitSemanticdb(root: Path, relDir: String): Unit =
    val semanticdb = root.resolve(relDir)
    Files.createDirectories(semanticdb)
    Files.writeString(semanticdb.resolve("Fixture.scala.semanticdb"), "")

  private def hookExit(root: Path, payload: String): Int =
    val hook = root.resolve(LauncherGuardHook.HookRelPath)
    scala.sys.process
      .Process(Seq("sh", hook.toString), root.toFile, "CLAUDE_PROJECT_DIR" -> root.toString)
      .#<(new java.io.ByteArrayInputStream(payload.getBytes("UTF-8")))
      .!(silentLogger)

  /** Exit code plus what the hook said, and where: Claude Code feeds stdout back to the agent as
    * context (a nudge) and stderr as the reason for a denial.
    */
  private def hookRun(root: Path, payload: String): (Int, String, String) =
    val hook = root.resolve(LauncherGuardHook.HookRelPath)
    val pb = java.lang.ProcessBuilder(java.util.List.of("sh", hook.toString))
    val _ = pb.directory(root.toFile)
    val _ = pb.environment().nn.put("CLAUDE_PROJECT_DIR", root.toString)
    val proc = pb.start().nn
    proc.getOutputStream.nn.write(payload.getBytes("UTF-8").nn)
    proc.getOutputStream.nn.close()
    val out = String(proc.getInputStream.nn.readAllBytes().nn, "UTF-8")
    val err = String(proc.getErrorStream.nn.readAllBytes().nn, "UTF-8")
    (proc.waitFor(), out, err)

  test("guard denies text tools aimed at Scala sources") {
    assume(hasJsonReader, "needs jq or python3")
    val root = guardedProject("ss-guard-deny")

    assertEquals(
      hookExit(root, """{"tool_name":"Read","tool_input":{"file_path":"src/Main.scala"}}"""),
      2,
      "Read of a .scala file must be denied"
    )
    assertEquals(
      hookExit(root, """{"tool_name":"Grep","tool_input":{"pattern":"foo","glob":"*.scala"}}"""),
      2,
      "Grep scoped to Scala files must be denied"
    )
    assertEquals(
      hookExit(root, """{"tool_name":"Bash","tool_input":{"command":"rg foo src/Main.scala"}}"""),
      2,
      "shell text search over a .scala file must be denied"
    )
  }

  test("guard stays out of the way of everything else") {
    assume(hasJsonReader, "needs jq or python3")
    val root = guardedProject("ss-guard-allow")

    val allowed = Seq(
      """{"tool_name":"Read","tool_input":{"file_path":"README.md"}}""",
      """{"tool_name":"Grep","tool_input":{"pattern":"foo","glob":"*.md"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"./mill __.compile"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"git diff src/Main.scala"}}""",
      // `.scala` inside a longer word is not a Scala path: blocking these blocks the build.
      """{"tool_name":"Bash","tool_input":{"command":"./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll | tail -3"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"cat .scala-build/log | head -5"}}""",
      // Running a Scala script is not reading it, and filtering its output is not a text search.
      """{"tool_name":"Bash","tool_input":{"command":"scala-cli scripts/smoke.sc | grep -E '^\\['"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"./mill foo.test src/Main.scala | tail -3"}}""",
      """{"tool_name":"Edit","tool_input":{"file_path":"src/Main.scala"}}"""
    )
    allowed.foreach(payload => assertEquals(hookExit(root, payload), 0, payload))
  }

  // --- edit-time steer -----------------------------------------------------------------------

  test("editing a Scala source is allowed, but reminds the agent of the annotated write path") {
    assume(hasJsonReader, "needs jq or python3")
    val root = guardedProject("ss-guard-edit-remind")

    val edits = Seq(
      """{"tool_name":"Edit","tool_input":{"file_path":"src/Main.scala"}}""",
      """{"tool_name":"Write","tool_input":{"file_path":"src/Main.scala"}}""",
      """{"tool_name":"MultiEdit","tool_input":{"file_path":"src/Main.sc"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"cat > src/Main.scala <<EOF"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"sed -i '' s/a/b/ src/Main.scala"}}"""
    )
    edits.foreach { payload =>
      val (code, out, _) = hookRun(root, payload)
      assertEquals(code, 0, s"an edit must never be blocked by default: $payload")
      assert(out.contains("annotated_source"), s"$payload produced no reminder:\n$out")
      assert(out.contains("sentinel"), s"$payload: reminder must name sentinel:\n$out")
      // sentinel alone still carries the line-number gutter, which write mode does not strip.
      assert(out.contains("compilable"), s"$payload: reminder must name format=compilable:\n$out")
    }
  }

  test("the edit reminder stays silent for everything that is not a Scala source") {
    assume(hasJsonReader, "needs jq or python3")
    val root = guardedProject("ss-guard-edit-quiet")

    val quiet = Seq(
      """{"tool_name":"Edit","tool_input":{"file_path":"README.md"}}""",
      """{"tool_name":"Edit","tool_input":{"file_path":"build.mill"}}""",
      """{"tool_name":"Write","tool_input":{"file_path":".claude/settings.json"}}""",
      """{"tool_name":"Bash","tool_input":{"command":"./mill __.compile"}}"""
    )
    quiet.foreach { payload =>
      val (code, out, _) = hookRun(root, payload)
      assertEquals(code, 0, payload)
      assertEquals(out.trim, "", s"$payload must produce no output:\n$out")
    }
  }

  test("--strict-edits denies edits of Scala sources and names the write path") {
    assume(hasJsonReader, "needs jq or python3")
    val root = tempProject("ss-guard-strict")
    runSetup(root, "--strict-edits")
    emitSemanticdb(root, "out/core/semanticDbData.dest/classes/META-INF/semanticdb")

    val (code, _, err) =
      hookRun(root, """{"tool_name":"Edit","tool_input":{"file_path":"src/Main.scala"}}""")
    assertEquals(code, 2, "strict mode must deny the edit")
    assert(err.contains("annotated_source"), s"the denial must say what to use instead:\n$err")

    val (mdCode, mdOut, _) =
      hookRun(root, """{"tool_name":"Edit","tool_input":{"file_path":"README.md"}}""")
    assertEquals(mdCode, 0, "strict mode still only covers Scala sources")
    assertEquals(mdOut.trim, "")
  }

  test("--strict-edits is off unless asked for") {
    assume(hasJsonReader, "needs jq or python3")
    val root = guardedProject("ss-guard-strict-default")
    assertEquals(
      hookExit(root, """{"tool_name":"Edit","tool_input":{"file_path":"src/Main.scala"}}"""),
      0
    )
  }

  test("setup widens the matcher of an install that predates the edit branch") {
    val old =
      """|{
         |  "hooks": {
         |    "PreToolUse": [
         |      {
         |        "matcher": "Read|Grep|Glob|Bash",
         |        "hooks": [
         |          { "type": "command", "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/scala-semantic-guard.sh" }
         |        ]
         |      }
         |    ]
         |  }
         |}
         |""".stripMargin

    val merged = LauncherGuardHook
      .mergeSettings(Some(old))
      .getOrElse(fail("an out-of-date matcher must be rewritten, not left alone"))

    assert(merged.contains(LauncherGuardHook.Matcher), merged)
    assert(!merged.contains("\"Read|Grep|Glob|Bash\""), s"the old matcher survived:\n$merged")
    assertEquals("scala-semantic-guard".r.findAllIn(merged).size, 1, merged)
    assertEquals(LauncherGuardHook.mergeSettings(Some(merged)), None, "must settle after one pass")
  }

  test("widening the matcher leaves the rest of settings.json alone") {
    val old =
      """|{
         |  "model": "opus",
         |  "hooks": {
         |    "PreToolUse": [
         |      { "matcher": "Bash", "hooks": [{ "type": "command", "command": "other.sh" }] },
         |      {
         |        "matcher": "Read|Grep|Glob|Bash",
         |        "hooks": [
         |          { "type": "command", "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/scala-semantic-guard.sh" }
         |        ]
         |      }
         |    ],
         |    "PostToolUse": [
         |      { "matcher": "Bash", "hooks": [{ "type": "command", "command": "log.sh" }] }
         |    ]
         |  }
         |}
         |""".stripMargin

    val merged = LauncherGuardHook
      .mergeSettings(Some(old))
      .getOrElse(fail("expected the matcher to be widened"))

    assert(merged.contains("\"model\": \"opus\""), merged)
    assert(merged.contains("other.sh"), merged)
    assert(merged.contains("log.sh"), merged)
    assert(merged.contains(LauncherGuardHook.Matcher), merged)
    assertEquals("scala-semantic-guard".r.findAllIn(merged).size, 1, merged)
  }

  test("an explicit semantic-fallback marker overrides the guard and is logged") {
    assume(hasJsonReader, "needs jq or python3")
    val root = guardedProject("ss-guard-fallback")
    val command = "rg foo src/Main.scala # semantic-fallback: index cannot answer this"

    assertEquals(
      hookExit(root, s"""{"tool_name":"Bash","tool_input":{"command":"$command"}}"""),
      0
    )
    val log = Files.readString(root.resolve(".claude/semantic-fallback.log"))
    assert(log.contains("semantic-fallback: index cannot answer this"), log)
  }

  test("guard sees an index under the build's output dir (Mill out/, sbt target/)") {
    assume(hasJsonReader, "needs jq or python3")
    val layouts = Seq(
      "out/core/semanticDbData.dest/classes/META-INF/semanticdb",
      "out/mcp/semanticDbData.super/classes/META-INF/semanticdb/com/example",
      "target/scala-3.8.4/classes/META-INF/semanticdb/src/main/scala",
      ".scala-build/project_abc/classes/main/META-INF/semanticdb"
    )
    layouts.foreach { rel =>
      val root = tempProject("ss-guard-index-layout")
      runSetup(root, "--rwhook-local")
      emitSemanticdb(root, rel)
      assertEquals(
        hookExit(root, """{"tool_name":"Read","tool_input":{"file_path":"src/Main.scala"}}"""),
        2,
        s"an index at $rel must count as usable, or the guard fails open on real projects"
      )
    }
  }

  test("guard fails open when no SemanticDB has been emitted yet") {
    assume(hasJsonReader, "needs jq or python3")
    val root = tempProject("ss-guard-no-index")
    runSetup(root, "--rwhook-local")

    assertEquals(
      hookExit(root, """{"tool_name":"Read","tool_input":{"file_path":"src/Main.scala"}}"""),
      0,
      "without an index the semantic tools cannot answer, so text tools must stay usable"
    )
  }
