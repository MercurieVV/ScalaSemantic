package com.github.mercurievv.scalasemantic.sbtplugin

import sbt.*
import sbt.Keys.*

import scala.sys.process.Process

/** Opt-in sbt plugin that wires the ScalaSemanticMCP server to a host project.
  *
  * It does NOT depend on the server (the server is Scala 3.8.4; an sbt plugin is built against the
  * meta-build's Scala). Instead it launches the server as an external process, which is what makes
  * the same approach work on sbt 1, sbt 2, and other build tools / a bare shell.
  *
  * Lifecycle is intentionally client-managed: an MCP stdio server is spawned by the MCP client
  * (e.g. Claude Code). This plugin's job is to (a) make the project emit SemanticDB, and (b) hand
  * the client a correct launch command via `mcpClientConfig`. `mcpRun` is for manual testing.
  *
  * Usage in a host build:
  * {{{
  *   enablePlugins(ScalaSemanticMcpPlugin)
  *   mcpServerCommand := Seq("/abs/path/to/scalasemantic-mcp") // or Seq("java", "-jar", "…jar")
  * }}}
  * then `sbt mcpClientConfig` prints a ready-to-paste `.mcp.json` entry.
  */
object ScalaSemanticMcpPlugin extends AutoPlugin:

  override def trigger = noTrigger // explicit opt-in via enablePlugins

  object autoImport:
    val mcpServerCommand =
      settingKey[Seq[String]]("argv that launches the MCP server (the generated launcher script, " +
        "or e.g. Seq(\"java\",\"-jar\",\"scalasemantic-mcp.jar\")). The SemanticDB root is appended.")
    val mcpServerName =
      settingKey[String]("name to register this server under in the MCP client")
    val mcpClientConfig =
      taskKey[Unit]("print the .mcp.json entry that registers this project's MCP server")
    val mcpRun =
      taskKey[Unit]("run the MCP server in the foreground (stdio) against this project")

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    semanticdbEnabled := true,
    mcpServerName := "scala-semantic",
    mcpServerCommand := Seq.empty,
    mcpClientConfig := {
      val log = streams.value.log
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
      val argv = resolvedCommand(mcpServerCommand.value, baseDirectory.value)
      val exit = Process(argv).!
      if exit != 0 then sys.error(s"MCP server exited with code $exit")
    }
  )

  /** Full argv = configured command + the project's SemanticDB root, or a clear error if unset. */
  private def resolvedCommand(command: Seq[String], baseDir: File): Seq[String] =
    if command.isEmpty then
      sys.error(
        "mcpServerCommand is empty — set it to the MCP server launch argv, e.g. the launcher " +
          "produced by `mcp/mcpLauncher`, or Seq(\"java\",\"-jar\",\"scalasemantic-mcp.jar\")."
      )
    else command :+ baseDir.getAbsolutePath
