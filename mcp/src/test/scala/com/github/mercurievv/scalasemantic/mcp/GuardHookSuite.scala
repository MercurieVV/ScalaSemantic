package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.Launcher
import com.github.mercurievv.scalasemantic.LauncherGuardHook

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

  test("setup installs the guard hook and registers it as a PreToolUse hook") {
    val root = tempProject("ss-guard-install")
    runSetup(root)

    val hook = root.resolve(LauncherGuardHook.HookRelPath)
    assert(Files.exists(hook), s"expected $hook")
    assert(Files.isExecutable(hook), s"$hook must be executable")

    val settings = Files.readString(root.resolve(".claude/settings.json"))
    assert(settings.contains("PreToolUse"), settings)
    assert(settings.contains("scala-semantic-guard.sh"), settings)
    assert(settings.contains("Read|Grep|Glob|Bash"), settings)
  }

  test("--no-guard skips the hook entirely") {
    val root = tempProject("ss-guard-optout")
    runSetup(root, "--no-guard")

    assert(!Files.exists(root.resolve(LauncherGuardHook.HookRelPath)))
    assert(!Files.exists(root.resolve(".claude/settings.json")))
  }

  test("re-running setup neither duplicates nor rewrites the registration") {
    val root = tempProject("ss-guard-idempotent")
    runSetup(root)
    val first = Files.readString(root.resolve(".claude/settings.json"))
    runSetup(root)
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

  // --- the two standalone installers must ship the same hook -------------------------------

  /** Reconstructs a `"""|…""".stripMargin` block, so the scala-cli script's copy can be compared
    * against the jar's without depending on how either file is indented.
    */
  private def stripMarginBlock(source: String, from: String): String =
    val lines = source.linesIterator.dropWhile(!_.contains(from)).toVector
    lines
      .takeWhile(!_.contains("\"\"\".stripMargin"))
      .map(_.replace("\"\"\"|", "|"))
      .map(line =>
        line.trim match
          case trimmed if trimmed.startsWith("|") => trimmed.drop(1)
          case _                                  => line
      )
      .mkString("\n") + "\n"

  test("the scala-cli installer ships the same guard script as the jar") {
    val script = Files.readString(Path.of("scripts/scalasemantic-mcp.scala"))
    assertEquals(stripMarginBlock(script, "#!/bin/sh"), LauncherGuardHook.script)
  }

  test("the PowerShell installer ships the same guard script as the jar") {
    val ps1 = Files.readString(Path.of("scripts/scalasemantic-mcp.ps1"))
    assert(ps1.contains(LauncherGuardHook.script.trim), "PowerShell guard body drifted")
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
    runSetup(root)
    val semanticdb = root.resolve("META-INF/semanticdb")
    Files.createDirectories(semanticdb)
    Files.writeString(semanticdb.resolve("Fixture.scala.semanticdb"), "")
    root

  private def hookExit(root: Path, payload: String): Int =
    val hook = root.resolve(LauncherGuardHook.HookRelPath)
    scala.sys.process
      .Process(Seq("sh", hook.toString), root.toFile, "CLAUDE_PROJECT_DIR" -> root.toString)
      .#<(new java.io.ByteArrayInputStream(payload.getBytes("UTF-8")))
      .!(silentLogger)

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
      """{"tool_name":"Edit","tool_input":{"file_path":"src/Main.scala"}}"""
    )
    allowed.foreach(payload => assertEquals(hookExit(root, payload), 0, payload))
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

  test("guard fails open when no SemanticDB has been emitted yet") {
    assume(hasJsonReader, "needs jq or python3")
    val root = tempProject("ss-guard-no-index")
    runSetup(root)

    assertEquals(
      hookExit(root, """{"tool_name":"Read","tool_input":{"file_path":"src/Main.scala"}}"""),
      0,
      "without an index the semantic tools cannot answer, so text tools must stay usable"
    )
  }
