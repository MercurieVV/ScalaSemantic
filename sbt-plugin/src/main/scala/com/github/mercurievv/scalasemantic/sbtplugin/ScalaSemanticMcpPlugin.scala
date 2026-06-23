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
  * Lifecycle is client-managed: an MCP stdio server is spawned by the MCP client (for example
  * Claude Code, Codex, Gemini CLI, or another MCP-capable coding agent).
  *
  * Usage in a host build — minimal:
  * {{{
  *   enablePlugins(ScalaSemanticMcpPlugin)
  * }}}
  * then `sbt mcpClientConfig` prints ready-to-paste MCP client configuration. Set `mcpClient` to
  * choose the output format. Override `mcpServerCommand` to point at a fixed jar or `cs` instead of
  * the bundled launcher if you prefer.
  */
object ScalaSemanticMcpPlugin extends AutoPlugin {

  override def trigger = noTrigger // explicit opt-in via enablePlugins

  object autoImport {
    val mcpServerCommand =
      settingKey[Seq[String]](
        "argv that launches the MCP server. Defaults to the bundled " +
          "auto-download launcher written by `mcpInstall`. The SemanticDB root is appended."
      )
    val mcpServerName =
      settingKey[String]("name to register this server under in the MCP client")
    val mcpClient =
      settingKey[String](
        "MCP client config dialect to print: claude, codex, gemini, cline, roo, continue, or generic-json"
      )
    @transient
    val mcpInstall =
      taskKey[File](
        "write the bundled auto-download launcher into a stable per-user dir (survives clean) " +
          "and return it"
      )
    @transient
    val mcpClasspathFile =
      taskKey[File](
        "write this project's compile classpath to a stable file and return it; the server reads " +
          "it to enable the presentation-compiler backend (live overlay of uncompiled buffers)"
      )
    @transient
    val mcpClientConfig =
      taskKey[Unit]("print the selected MCP client config that registers this project's server")
    @transient
    val mcpRun =
      taskKey[Unit]("run the MCP server in the foreground (stdio) against this project")
  }

  import autoImport._

  override def projectSettings = Seq(
    semanticdbEnabled := true,
    mcpServerName := "scala-semantic",
    mcpClient := "claude",
    mcpInstall := writeLauncher(installDir, streams.value.log),
    mcpClasspathFile := {
      // The PC backend needs the target project's COMPILE classpath (deps + its own output) to
      // resolve everything a buffer references. sbt 2.0 classpaths are virtual-file refs, so resolve
      // to real paths via the file converter (same pattern as the dev launcher). Written to a stable
      // per-user file (survives clean) the server reads at startup; rerun after dependency changes.
      val converter = fileConverter.value
      val cp = (Compile / fullClasspath).value
        .map(af => ClasspathCompat.toAbsolutePath(af.data, converter))
      val out = classpathFile(mcpServerName.value)
      IO.write(out, cp.mkString("\n"))
      streams.value.log.info(s"MCP classpath written (${cp.size} entries): $out")
      out
    },
    // Default to invoking the launcher this plugin writes. Resolved against the same STABLE path
    // mcpInstall uses (not target/, which `clean` would wipe out from under a persistent .mcp.json),
    // so `mcpClientConfig`/`mcpRun` produce a command that keeps existing.
    mcpServerCommand := launcherCommand(installDir / launcherName),
    mcpClientConfig := {
      val log = streams.value.log
      val launcher =
        mcpInstall.value // ensure the launcher exists before we print a command pointing at it
      // Blocking prefetch: download + cache the server jar NOW, while the user is setting up, so the
      // first real client connect hits a warm cache instead of racing its connect timeout while the
      // ~88 MB jar downloads. The launcher's `--prefetch` fetches + caches, then exits without
      // serving. Best-effort — config printing must still work offline.
      try {
        log.info("Prefetching the MCP server (one-time download; may take a moment)...")
        val rc = Process(launcherCommand(launcher) :+ "--prefetch").!
        if (rc != 0)
          log.warn(s"MCP server prefetch returned $rc; it will download on first connect instead.")
      } catch {
        case scala.util.control.NonFatal(e) =>
          log.warn(
            s"MCP server prefetch skipped (${e.getMessage}); it will download on first connect."
          )
      }
      // Reference the classpath file by PATH only — do NOT depend on `mcpClasspathFile`, which
      // evaluates `Compile / fullClasspath` and so forces a full compile. Printing a config entry
      // must work even when the project does not compile. The server treats a not-yet-written
      // classpath file as index-only; run `sbt mcpClasspathFile` once (needs a clean compile) to
      // enable the live presentation-compiler backend.
      val cpFile = classpathFile(mcpServerName.value)
      // Enable the server's file log in the generated config: `--log` (startup + per-tool-call) and
      // `--log-output` (also each response sent to the model). Flags are position-independent; drop
      // them from the printed `.mcp.json` if you prefer the silent default.
      val argv =
        resolvedCommand(mcpServerCommand.value, baseDirectory.value, cpFile) ++
          Seq("--log", "--log-output")
      val rendered = renderClientConfig(mcpClient.value, mcpServerName.value, argv)
      log.info(s"Register this in ${rendered.destination}:\n${rendered.config}")
      if (!cpFile.exists)
        log.info(
          "Server will run index-only. Run `sbt mcpClasspathFile` once (requires a successful " +
            "compile) to enable the live presentation-compiler backend."
        )
      // The server answers from SemanticDB, which is compiler output. If the project has never been
      // compiled, the index is empty and every query returns nothing — warn instead of letting that
      // look like a broken server. Cheap filesystem check only; does not trigger a compile.
      if (!hasSemanticdb(baseDirectory.value))
        log.warn(
          "No SemanticDB found under target/ — the server will start with an empty index and every " +
            "query will return nothing. Run `sbt compile` once first so it has symbols to answer from."
        )
    },
    mcpRun := {
      val _ = mcpInstall.value
      val argv =
        resolvedCommand(mcpServerCommand.value, baseDirectory.value, mcpClasspathFile.value)
      val exit = Process(argv).!
      if (exit != 0) sys.error(s"MCP server exited with code $exit")
    }
  )

  /** Stable path of the classpath file for a given server name. Computed (not built) so config
    * printing can reference it without triggering a compile; [[mcpClasspathFile]] writes it here.
    */
  private def classpathFile(serverName: String): File =
    installDir / s"$serverName-classpath.txt"

  /** True if any `*.semanticdb` file exists under `<baseDir>/target` — i.e. the project has been
    * compiled at least once with SemanticDB on, so the server has something to index. Pure
    * filesystem walk, bounded to `target/`; never triggers a compile. Best-effort: any error or a
    * missing `target/` reads as "none".
    */
  private def hasSemanticdb(baseDir: File): Boolean = {
    val target = baseDir / "target"
    if (!target.isDirectory) false
    else
      try {
        var stream: java.util.stream.Stream[java.nio.file.Path] = null
        try {
          stream = java.nio.file.Files.walk(target.toPath)
          stream.anyMatch(p => p.getFileName.toString.endsWith(".semanticdb"))
        } finally if (stream != null) stream.close()
      } catch { case _: Throwable => false }
  }

  /** OS-specific launcher file name (the resource bundled in the plugin jar). */
  private def launcherName: String =
    if (isWindows) "scalasemantic-mcp.ps1" else "scalasemantic-mcp.sh"

  private def isWindows: Boolean =
    sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** Stable per-user dir for the installed launcher — survives `clean` (unlike target/) and is the
    * same convention as scripts/install.sh: `~/.local/bin` on Unix, `%LOCALAPPDATA%\…` on Windows.
    */
  private def installDir: File = {
    val home = file(sys.props.getOrElse("user.home", "."))
    if (isWindows)
      file(sys.env.getOrElse("LOCALAPPDATA", (home / "AppData" / "Local").getAbsolutePath)) /
        "scalasemantic-mcp" / "bin"
    else home / ".local" / "bin"
  }

  /** argv prefix to invoke a launcher script — PowerShell needs an explicit host, sh is executable.
    */
  private def launcherCommand(script: File): Seq[String] =
    if (script.getName.endsWith(".ps1"))
      Seq("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.getAbsolutePath)
    else Seq(script.getAbsolutePath)

  /** Copy the bundled launcher resource into `<target>/` and mark it executable. */
  private def writeLauncher(targetDir: File, log: Logger): File = {
    val name = launcherName
    val out = targetDir / name
    val res = s"scalasemantic/$name"
    val in = Option(getClass.getClassLoader.getResourceAsStream(res))
      .getOrElse(sys.error(s"bundled launcher resource not found: $res"))
    try
      IO.write(out, in.readAllBytes())
    finally in.close()
    val _ = out.setExecutable(true)
    log.info(s"MCP launcher written: $out")
    out
  }

  /** Full argv = configured command + the project's SemanticDB root + the classpath file (which
    * enables the PC backend), or a clear error if the command is unset.
    */
  private def resolvedCommand(
      command: Seq[String],
      baseDir: File,
      classpathFile: File
  ): Seq[String] =
    if (command.isEmpty)
      sys.error(
        "mcpServerCommand is empty — leave it at its default (the bundled launcher) or set it to a " +
          "launch argv, e.g. Seq(\"java\",\"-jar\",\"scalasemantic-mcp.jar\")."
      )
    else command :+ baseDir.getAbsolutePath :+ classpathFile.getAbsolutePath

  private final case class RenderedConfig(destination: String, config: String)

  private def renderClientConfig(
      client: String,
      serverName: String,
      argv: Seq[String]
  ): RenderedConfig = {
    val normalized = client.trim.toLowerCase.replace('_', '-')
    normalized match {
      case "codex" | "openai" | "openai-codex" =>
        RenderedConfig(
          "Codex config.toml (for example ~/.codex/config.toml or .codex/config.toml)",
          renderCodexToml(serverName, argv)
        )
      case "claude" | "claude-code" | "anthropic" =>
        RenderedConfig("Claude Code .mcp.json", renderMcpJson(serverName, argv, Nil))
      case "gemini" | "google" | "google-gemini" | "gemini-cli" =>
        RenderedConfig(
          "Gemini CLI settings.json (for example ~/.gemini/settings.json or .gemini/settings.json)",
          renderMcpJson(serverName, argv, Seq("timeout" -> "60000"))
        )
      case "cline" =>
        RenderedConfig(
          "Cline MCP JSON (for example ~/.cline/mcp.json or the IDE MCP settings JSON)",
          renderMcpJson(serverName, argv, Seq("disabled" -> "false", "autoApprove" -> "[]"))
        )
      case "roo" | "roo-code" =>
        RenderedConfig(
          "Roo Code MCP JSON (for example .roo/mcp.json or global mcp_settings.json)",
          renderMcpJson(
            serverName,
            argv,
            Seq("disabled" -> "false", "alwaysAllow" -> "[]", "timeout" -> "60")
          )
        )
      case "continue" | "continue-dev" =>
        RenderedConfig("Continue config.yaml", renderContinueYaml(serverName, argv))
      case "generic" | "generic-json" | "json" | "oss" | "open-source" | "free" =>
        RenderedConfig("a generic MCP client JSON config", renderMcpJson(serverName, argv, Nil))
      case other =>
        sys.error(
          s"Unsupported mcpClient '$other'. Use one of: " +
            "claude, codex, gemini, cline, roo, continue, generic-json."
        )
    }
  }

  private def renderMcpJson(
      serverName: String,
      argv: Seq[String],
      extraFields: Seq[(String, String)]
  ): String = {
    val argsJson = argv.tail.map(jsonString).mkString("[", ", ", "]")
    val extra =
      extraFields.map { case (name, value) => s",\n      ${jsonString(name)}: $value" }.mkString
    s"""|{
        |  "mcpServers": {
        |    ${jsonString(serverName)}: {
        |      "command": ${jsonString(argv.head)},
        |      "args": $argsJson$extra
        |    }
        |  }
        |}""".stripMargin
  }

  private def renderCodexToml(serverName: String, argv: Seq[String]): String = {
    val argsToml = argv.tail.map(tomlString).mkString("[", ", ", "]")
    s"""|[mcp_servers.${tomlKey(serverName)}]
        |command = ${tomlString(argv.head)}
        |args = $argsToml
        |startup_timeout_sec = 60
        |tool_timeout_sec = 60""".stripMargin
  }

  private def renderContinueYaml(serverName: String, argv: Seq[String]): String = {
    val args =
      if (argv.tail.isEmpty) ""
      else argv.tail.map(a => s"\n      - ${yamlString(a)}").mkString("\n    args:", "", "")
    s"""|name: ScalaSemantic MCP
        |version: 1.0.0
        |schema: v1
        |mcpServers:
        |  - name: ${yamlString(serverName)}
        |    command: ${yamlString(argv.head)}$args
        |    connectionTimeout: 60000""".stripMargin
  }

  private def tomlKey(value: String): String =
    if (value.matches("[A-Za-z0-9_-]+")) value else tomlString(value)

  private def jsonString(value: String): String =
    "\"" + value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\b'         => "\\b"
      case '\f'         => "\\f"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => "\\u%04x".format(c.toInt)
      case c            => c.toString
    } + "\""

  private def tomlString(value: String): String =
    "\"" + value.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\t' => "\\t"
      case '\n' => "\\n"
      case '\f' => "\\f"
      case '\r' => "\\r"
      case c    => c.toString
    } + "\""

  private def yamlString(value: String): String =
    "\"" + value.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\t' => "\\t"
      case '\n' => "\\n"
      case '\f' => "\\f"
      case '\r' => "\\r"
      case c    => c.toString
    } + "\""
}
