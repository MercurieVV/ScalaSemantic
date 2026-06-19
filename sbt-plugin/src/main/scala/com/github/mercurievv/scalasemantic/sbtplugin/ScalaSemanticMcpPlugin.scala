package com.github.mercurievv.scalasemantic.sbtplugin

import sbt.*
import sbt.Keys.*

import scala.sys.process.Process

/** Opt-in sbt plugin that wires the ScalaSemanticMCP server to a host project.
  *
  * It does NOT depend on the server (the server is Scala 3.8.4; an sbt plugin is built against the
  * meta-build's Scala). Instead it makes the project emit SemanticDB and hands the MCP client a
  * launch command. By default that command is the bundled auto-download launcher script, which
  * fetches + runs the server (via coursier if present, else the GitHub-Release fat jar) — so the
  * server binary need not be installed ahead of time. The same approach works on sbt 1, sbt 2,
  * other build tools, and a bare shell.
  *
  * Lifecycle is client-managed: an MCP stdio server is spawned by the MCP client (e.g. Claude
  * Code).
  *
  * Usage in a host build — minimal:
  * {{{
  *   enablePlugins(ScalaSemanticMcpPlugin)
  * }}}
  * then `sbt mcpClientConfig` prints a ready-to-paste `.mcp.json` entry. Override
  * `mcpServerCommand` to point at a fixed jar or `cs` instead of the bundled launcher if you
  * prefer.
  */
object ScalaSemanticMcpPlugin extends AutoPlugin:

  override def trigger = noTrigger // explicit opt-in via enablePlugins

  object autoImport:
    val mcpServerCommand =
      settingKey[Seq[String]](
        "argv that launches the MCP server. Defaults to the bundled " +
          "auto-download launcher written by `mcpInstall`. The SemanticDB root is appended."
      )
    val mcpServerName =
      settingKey[String]("name to register this server under in the MCP client")
    val mcpInstall =
      taskKey[File]("write the bundled auto-download launcher script into target and return it")
    val mcpClientConfig =
      taskKey[Unit]("print the .mcp.json entry that registers this project's MCP server")
    val mcpRun =
      taskKey[Unit]("run the MCP server in the foreground (stdio) against this project")

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    semanticdbEnabled := true,
    mcpServerName := "scala-semantic",
    mcpInstall := Def.uncached(writeLauncher(target.value, streams.value.log)),
    // Default to invoking the launcher this plugin writes. Resolved against the same path mcpInstall
    // uses, so `mcpClientConfig`/`mcpRun` (which run mcpInstall) produce a command that exists.
    mcpServerCommand := launcherCommand(target.value / launcherName),
    mcpClientConfig := {
      val log = streams.value.log
      mcpInstall.value // ensure the launcher exists on disk before we print a command pointing at it
      val argv = resolvedCommand(mcpServerCommand.value, baseDirectory.value)
      val argsJson = argv.tail.map(a => "\"" + a + "\"").mkString("[", ", ", "]")
      log.info(
        s"""|Register this in your MCP client (e.g. .mcp.json):
            |{
            |  "mcpServers": {
            |    "${mcpServerName.value}": {
            |      "command": "${argv.head}",
            |      "args": $argsJson
            |    }
            |  }
            |}""".stripMargin
      )
    },
    mcpRun := {
      mcpInstall.value
      val argv = resolvedCommand(mcpServerCommand.value, baseDirectory.value)
      val exit = Process(argv).!
      if exit != 0 then sys.error(s"MCP server exited with code $exit")
    }
  )

  /** OS-specific launcher file name (the resource bundled in the plugin jar). */
  private def launcherName: String =
    if sys.props.getOrElse("os.name", "").toLowerCase.contains("win") then "scalasemantic-mcp.ps1"
    else "scalasemantic-mcp.sh"

  /** argv prefix to invoke a launcher script — PowerShell needs an explicit host, sh is executable.
    */
  private def launcherCommand(script: File): Seq[String] =
    if script.getName.endsWith(".ps1") then
      Seq("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.getAbsolutePath)
    else Seq(script.getAbsolutePath)

  /** Copy the bundled launcher resource into `<target>/` and mark it executable. */
  private def writeLauncher(targetDir: File, log: Logger): File =
    val name = launcherName
    val out = targetDir / name
    val res = s"scalasemantic/$name"
    val in = Option(getClass.getClassLoader.getResourceAsStream(res))
      .getOrElse(sys.error(s"bundled launcher resource not found: $res"))
    try
      IO.write(out, in.readAllBytes())
    finally in.close()
    out.setExecutable(true)
    log.info(s"MCP launcher written: $out")
    out

  /** Full argv = configured command + the project's SemanticDB root, or a clear error if unset. */
  private def resolvedCommand(command: Seq[String], baseDir: File): Seq[String] =
    if command.isEmpty then
      sys.error(
        "mcpServerCommand is empty — leave it at its default (the bundled launcher) or set it to a " +
          "launch argv, e.g. Seq(\"java\",\"-jar\",\"scalasemantic-mcp.jar\")."
      )
    else command :+ baseDir.getAbsolutePath
