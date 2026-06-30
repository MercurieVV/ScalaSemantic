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
  * then `sbt mcpClientConfig` writes the MCP client configuration into a project-local file
  * (`.mcp.json`, `.codex/config.toml`, …), merging this server's entry into any existing file
  * without disturbing other servers or settings. Set `mcpClient` to choose the output format.
  * Override `mcpServerCommand` to point at a fixed jar or `cs` instead of the bundled launcher if
  * you prefer.
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
        "MCP client config dialect to write: claude, codex, gemini, cline, roo, continue, antigravity, generic-json, or all"
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
      inputKey[Unit](
        "write/merge the selected MCP client config (project-local file) that registers this " +
          "project's server. Pass 'all' to generate configurations for all supported LLM clients."
      )
    @transient
    val mcpRun =
      taskKey[Unit]("run the MCP server in the foreground (stdio) against this project")
  }

  import autoImport._

  override def projectSettings = Seq(
    semanticdbEnabled := true,
    mcpServerName := "scala-semantic",
    mcpClient := "claude",
    mcpInstall := writeLauncher(installDir),
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
      out
    },
    // Default to invoking the launcher this plugin writes. Resolved against the same STABLE path
    // mcpInstall uses (not target/, which `clean` would wipe out from under a persistent .mcp.json),
    // so `mcpClientConfig`/`mcpRun` produce a command that keeps existing.
    mcpServerCommand := launcherCommand(installDir / launcherName),
    mcpClientConfig := {
      val args = sbt.complete.DefaultParsers.spaceDelimited("<client>").parsed
      val log = streams.value.log
      val launcher =
        mcpInstall.value // ensure the launcher exists before we print a command pointing at it
      // Blocking prefetch: download + cache the server jar NOW, while the user is setting up, so the
      // first real client connect hits a warm cache instead of racing its connect timeout while the
      // ~88 MB jar downloads. The launcher's `--prefetch` fetches + caches, then exits without
      // serving. Best-effort — config printing must still work offline.
      try {
        val _ = Process(launcherCommand(launcher) :+ "--prefetch").!
      } catch {
        case scala.util.control.NonFatal(_) =>
          // Silently continue if prefetch fails; the server will download on first connect
      }
      // Reference the classpath file by PATH only — do NOT depend on `mcpClasspathFile`, which
      // evaluates `Compile / fullClasspath` and so forces a full compile. Printing a config entry
      // must work even when the project does not compile. The server treats a not-yet-written
      // classpath file as index-only; run `sbt mcpClasspathFile` once (needs a clean compile) to
      // enable the live presentation-compiler backend.
      val cpFile = classpathFile(mcpServerName.value)
      // Logging is silent by default. To enable it, add `--log` and/or `--log-output` to args
      // in the generated config, or set SCALASEMANTIC_LOG=1 and/or SCALASEMANTIC_LOG_OUTPUT=1.
      val argv =
        resolvedCommand(mcpServerCommand.value, baseDirectory.value, cpFile)
      val serverName = mcpServerName.value
      val clientVal = args.headOption.getOrElse(mcpClient.value)
      val clients = if (clientVal.trim.toLowerCase == "all") {
        Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity")
      } else {
        Seq(clientVal)
      }
      for (client <- clients) {
        val target = ScalaSemanticConfigMerger.targetFor(client)
        val outFile = baseDirectory.value / target.relPath
        val existing = if (outFile.exists) Some(IO.read(outFile)) else None
        val merged = target.fmt match {
          case ScalaSemanticConfigMerger.JsonFmt =>
            ScalaSemanticConfigMerger.mergeJson(existing, serverName, argv, target.extraJson)
          case ScalaSemanticConfigMerger.TomlFmt =>
            ScalaSemanticConfigMerger.mergeToml(existing, serverName, argv)
          case ScalaSemanticConfigMerger.YamlFmt =>
            ScalaSemanticConfigMerger.mergeYaml(existing, serverName, argv)
        }
        // IO.write creates parent dirs. Merge inserts/replaces only this server's entry, leaving any
        // other servers and unrelated settings in the file untouched.
        IO.write(outFile, merged)
        ScalaSemanticConfigMerger.writeRulesAndSteer(client, baseDirectory.value, log)
      }
      // cpFile check - no logging needed; server handles missing classpath file gracefully
      // The server answers from SemanticDB, which is compiler output. Cheap filesystem check only;
      // does not trigger a compile. If needed, users can check SemanticDB status manually.
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
  private def writeLauncher(targetDir: File): File = {
    val name = launcherName
    val out = targetDir / name
    val res = s"scalasemantic/$name"
    val in = Option(getClass.getClassLoader.getResourceAsStream(res))
      .getOrElse(sys.error(s"bundled launcher resource not found: $res"))
    try
      IO.write(out, in.readAllBytes())
    finally in.close()
    val _ = out.setExecutable(true)
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

}
