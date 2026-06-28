package com.github.mercurievv.scalasemantic.sbtplugin

import sbt.*
import sbt.Keys.*

import scala.annotation.tailrec
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
        "MCP client config dialect to write: claude, codex, gemini, cline, roo, continue, or generic-json"
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
          "project's server"
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
      val args = sbt.complete.DefaultParsers.spaceDelimited("<client>").parsed
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
      val serverName = mcpServerName.value
      val clientVal = args.headOption.getOrElse(mcpClient.value)
      val target = targetFor(clientVal)
      val outFile = baseDirectory.value / target.relPath
      val existing = if (outFile.exists) Some(IO.read(outFile)) else None
      val merged = target.fmt match {
        case JsonFmt => mergeJson(existing, serverName, argv, target.extraJson)
        case TomlFmt => mergeToml(existing, serverName, argv)
        case YamlFmt => mergeYaml(existing, serverName, argv)
      }
      // IO.write creates parent dirs. Merge inserts/replaces only this server's entry, leaving any
      // other servers and unrelated settings in the file untouched.
      IO.write(outFile, merged)
      val verb = if (existing.isEmpty) "Wrote" else "Merged into"
      log.info(s"$verb MCP config for server '$serverName': $outFile")
      writeRulesAndSteer(clientVal, baseDirectory.value, log)
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

  // --- Config target + merge -------------------------------------------------------------------
  // mcpClientConfig writes (and merges) into a project-local file rather than just printing, so the
  // entry survives and lives next to the project. Three on-disk formats: JSON, TOML, YAML. Each
  // merge inserts-or-replaces ONLY this server's entry and leaves the rest of the file untouched —
  // done with a small format-aware scanner rather than a JSON/TOML/YAML library, because the plugin
  // cross-builds to Scala 2.12 / sbt 1.x where pulling such a dep onto the plugin classpath is
  // fragile.

  private sealed trait Fmt
  private case object JsonFmt extends Fmt
  private case object TomlFmt extends Fmt
  private case object YamlFmt extends Fmt

  /** Where this client's config lives (project-local), its on-disk format, and the client-specific
    * extra JSON fields to emit alongside command/args.
    */
  private final case class Target(relPath: String, fmt: Fmt, extraJson: Seq[(String, String)])

  private def targetFor(client: String): Target = {
    val normalized = client.trim.toLowerCase.replace('_', '-')
    normalized match {
      case "codex" | "openai" | "openai-codex" =>
        Target(".codex/config.toml", TomlFmt, Nil)
      case "claude" | "claude-code" | "anthropic" =>
        Target(".mcp.json", JsonFmt, Nil)
      case "gemini" | "google" | "google-gemini" | "gemini-cli" =>
        Target(".gemini/settings.json", JsonFmt, Seq("timeout" -> "60000"))
      case "cline" =>
        Target(".cline/mcp.json", JsonFmt, Seq("disabled" -> "false", "autoApprove" -> "[]"))
      case "roo" | "roo-code" =>
        Target(
          ".roo/mcp.json",
          JsonFmt,
          Seq("disabled" -> "false", "alwaysAllow" -> "[]", "timeout" -> "60")
        )
      case "continue" | "continue-dev" =>
        Target(".continue/config.yaml", YamlFmt, Nil)
      case "generic" | "generic-json" | "json" | "oss" | "open-source" | "free" =>
        Target(".mcp.json", JsonFmt, Nil)
      case other =>
        sys.error(
          s"Unsupported mcpClient '$other'. Use one of: " +
            "claude, codex, gemini, cline, roo, continue, generic-json."
        )
    }
  }

  // --- JSON -------------------------------------------------------------------------------------

  /** The value object for one server key, e.g. `{ "command": ..., "args": [...]<extra> }`, with
    * fields indented 6 spaces and the closing brace at 4 — matching [[renderMcpJson]].
    */
  private def jsonEntry(argv: Seq[String], extraFields: Seq[(String, String)]): String = {
    val argsJson = argv.tail.map(jsonString).mkString("[", ", ", "]")
    val extra =
      extraFields.map { case (name, value) => s",\n      ${jsonString(name)}: $value" }.mkString
    s"""|{
        |      "command": ${jsonString(argv.head)},
        |      "args": $argsJson$extra
        |    }""".stripMargin
  }

  /** A standalone `.mcp.json`-style document (used when the target file does not yet exist). */
  private def renderMcpJson(
      serverName: String,
      argv: Seq[String],
      extraFields: Seq[(String, String)]
  ): String =
    s"""|{
        |  "mcpServers": {
        |    ${jsonString(serverName)}: ${jsonEntry(argv, extraFields)}
        |  }
        |}""".stripMargin

  /** Insert-or-replace this server under the top-level `mcpServers` object, preserving everything
    * else. Falls back to a fresh document if the file is absent/blank or not a JSON object.
    */
  private[sbtplugin] def mergeJson(
      existing: Option[String],
      serverName: String,
      argv: Seq[String],
      extraFields: Seq[(String, String)]
  ): String = {
    val fresh = renderMcpJson(serverName, argv, extraFields)
    val src = existing.getOrElse("")
    val rootOpen = if (src.trim.isEmpty) -1 else src.indexOf('{')
    val rootClose = if (rootOpen < 0) -1 else matchBracket(src, rootOpen)
    if (rootClose < 0) fresh // absent/blank/not a JSON object — write a fresh document
    else {
      val entry = jsonEntry(argv, extraFields)
      val msKey = findJsonKey(src, rootOpen + 1, rootClose, "mcpServers")
      if (msKey < 0) {
        // No mcpServers object yet — add one to the root object.
        val hadEntries = src.substring(rootOpen + 1, rootClose).trim.nonEmpty
        val block = s"""\n  "mcpServers": {\n    ${jsonString(serverName)}: $entry\n  }"""
        val comma = if (hadEntries) "," else ""
        src.substring(0, rootOpen + 1) + block + comma + src.substring(rootOpen + 1)
      } else {
        val objOpen = src.indexOf('{', src.indexOf(':', msKey))
        val objClose = matchBracket(src, objOpen)
        val snKey = findJsonKey(src, objOpen + 1, objClose, serverName)
        if (snKey >= 0) {
          // Replace just this server's value.
          val vs = skipWs(src, src.indexOf(':', snKey) + 1, objClose)
          val ve = jsonValueEnd(src, vs, objClose)
          src.substring(0, vs) + entry + src.substring(ve)
        } else {
          // Insert this server as the first entry of the existing mcpServers object.
          val hadEntries = src.substring(objOpen + 1, objClose).trim.nonEmpty
          val ins = s"""\n    ${jsonString(serverName)}: $entry${if (hadEntries) "," else ""}"""
          src.substring(0, objOpen + 1) + ins + src.substring(objOpen + 1)
        }
      }
    }
  }

  /** First index >= `i` (and < `limit`) whose char is not whitespace. */
  @tailrec private def skipWs(s: String, i: Int, limit: Int): Int =
    if (i < limit && s.charAt(i).isWhitespace) skipWs(s, i + 1, limit) else i

  /** Normalize to exactly one trailing newline so re-running the merge on its own output is a
    * fixpoint (the append and replace paths would otherwise differ only in the EOF newline).
    */
  private def withTrailingNewline(s: String): String =
    s.replaceAll("\\R+\\z", "") + "\n"

  /** Number of leading space characters on a line (its YAML indentation depth). */
  private def leadingSpaces(line: String): Int = line.takeWhile(_ == ' ').length

  /** Index just past the value (object/array/string/primitive) that starts at `vs`. */
  private def jsonValueEnd(s: String, vs: Int, limit: Int): Int =
    s.charAt(vs) match {
      case '{' | '[' => matchBracket(s, vs) + 1
      case '"' =>
        @tailrec def str(i: Int, esc: Boolean): Int =
          if (i >= limit) limit
          else {
            val c = s.charAt(i)
            if (esc) str(i + 1, esc = false)
            else if (c == '\\') str(i + 1, esc = true)
            else if (c == '"') i + 1
            else str(i + 1, esc = false)
          }
        str(vs + 1, esc = false)
      case _ =>
        @tailrec def scan(i: Int): Int =
          if (i < limit && s.charAt(i) != ',' && s.charAt(i) != '}') scan(i + 1) else i
        @tailrec def back(i: Int): Int =
          if (i > vs && s.charAt(i - 1).isWhitespace) back(i - 1) else i
        back(scan(vs))
    }

  /** Index of the closing bracket matching the `{`/`[` at `openIdx`, honoring strings/escapes. */
  private def matchBracket(s: String, openIdx: Int): Int = {
    @tailrec def loop(i: Int, depth: Int, inStr: Boolean, esc: Boolean): Int =
      if (i >= s.length) -1
      else {
        val c = s.charAt(i)
        if (inStr) {
          if (esc) loop(i + 1, depth, inStr = true, esc = false)
          else if (c == '\\') loop(i + 1, depth, inStr = true, esc = true)
          else if (c == '"') loop(i + 1, depth, inStr = false, esc = false)
          else loop(i + 1, depth, inStr = true, esc = false)
        } else
          c match {
            case '"'       => loop(i + 1, depth, inStr = true, esc = false)
            case '{' | '[' => loop(i + 1, depth + 1, inStr = false, esc = false)
            case '}' | ']' =>
              if (depth - 1 == 0) i else loop(i + 1, depth - 1, inStr = false, esc = false)
            case _ => loop(i + 1, depth, inStr = false, esc = false)
          }
      }
    loop(openIdx, 0, inStr = false, esc = false)
  }

  /** True if the first non-whitespace char at or after `from` (and before `end`) is a colon. */
  private def colonFollows(s: String, from: Int, end: Int): Boolean = {
    val j = skipWs(s, from, end)
    j < end && s.charAt(j) == ':'
  }

  /** Index of the key string `"key"` that appears at depth 0 within `[start, end)` and is followed
    * by a colon, or -1. Assumes `key` needs no JSON escaping (true for our server names).
    */
  private def findJsonKey(s: String, start: Int, end: Int, key: String): Int = {
    val target = "\"" + key + "\""
    @tailrec def loop(i: Int, depth: Int, inStr: Boolean, esc: Boolean): Int =
      if (i >= end) -1
      else {
        val c = s.charAt(i)
        if (inStr) {
          if (esc) loop(i + 1, depth, inStr = true, esc = false)
          else if (c == '\\') loop(i + 1, depth, inStr = true, esc = true)
          else if (c == '"') loop(i + 1, depth, inStr = false, esc = false)
          else loop(i + 1, depth, inStr = true, esc = false)
        } else
          c match {
            case '"' =>
              if (
                depth == 0 && s.regionMatches(i, target, 0, target.length) &&
                colonFollows(s, i + target.length, end)
              ) i
              else loop(i + 1, depth, inStr = true, esc = false)
            case '{' | '[' => loop(i + 1, depth + 1, inStr = false, esc = false)
            case '}' | ']' => loop(i + 1, depth - 1, inStr = false, esc = false)
            case _         => loop(i + 1, depth, inStr = false, esc = false)
          }
      }
    loop(start, 0, inStr = false, esc = false)
  }

  // --- TOML -------------------------------------------------------------------------------------

  private def renderCodexToml(serverName: String, argv: Seq[String]): String = {
    val argsToml = argv.tail.map(tomlString).mkString("[", ", ", "]")
    s"""|[mcp_servers.${tomlKey(serverName)}]
        |command = ${tomlString(argv.head)}
        |args = $argsToml
        |startup_timeout_sec = 60
        |tool_timeout_sec = 60""".stripMargin
  }

  /** Replace the `[mcp_servers.<name>]` table (header through the line before the next `[...]`
    * table, or EOF) if present, else append it. Other tables stay put.
    */
  private[sbtplugin] def mergeToml(
      existing: Option[String],
      serverName: String,
      argv: Seq[String]
  ): String = {
    val fresh = renderCodexToml(serverName, argv)
    val src = existing.getOrElse("")
    val body =
      if (src.trim.isEmpty) fresh
      else {
        val header = s"[mcp_servers.${tomlKey(serverName)}]"
        val lines = src.split("\n", -1).toVector
        val idx = lines.indexWhere(_.trim == header)
        if (idx < 0) {
          val sep = if (src.endsWith("\n")) "" else "\n"
          src + sep + "\n" + fresh
        } else {
          // The table runs until the next `[...]` table header, or EOF.
          val end = lines.indexWhere(_.trim.startsWith("["), idx + 1) match {
            case -1 => lines.length
            case e  => e
          }
          (lines.take(idx) ++ fresh.split("\n", -1) ++ lines.drop(end)).mkString("\n")
        }
      }
    withTrailingNewline(body)
  }

  // --- YAML (Continue) --------------------------------------------------------------------------

  /** One `mcpServers` list item (indented for a 2-space list). */
  private def continueItem(serverName: String, argv: Seq[String]): String = {
    val args =
      if (argv.tail.isEmpty) ""
      else argv.tail.map(a => s"\n      - ${yamlString(a)}").mkString("\n    args:", "", "")
    s"""|  - name: ${yamlString(serverName)}
        |    command: ${yamlString(argv.head)}$args
        |    connectionTimeout: 60000""".stripMargin
  }

  private def renderContinueYaml(serverName: String, argv: Seq[String]): String =
    s"""|name: ScalaSemantic MCP
        |version: 1.0.0
        |schema: v1
        |mcpServers:
        |${continueItem(serverName, argv)}""".stripMargin

  /** Insert-or-replace this server's list item under `mcpServers:`, else append the block. */
  private[sbtplugin] def mergeYaml(
      existing: Option[String],
      serverName: String,
      argv: Seq[String]
  ): String = {
    val fresh = renderContinueYaml(serverName, argv)
    val src = existing.getOrElse("")
    val body =
      if (src.trim.isEmpty) fresh
      else {
        val item = continueItem(serverName, argv)
        val lines = src.split("\n", -1).toVector
        val msIdx = lines.indexWhere(_.matches("""mcpServers:\s*"""))
        if (msIdx < 0) {
          val sep = if (src.endsWith("\n")) "" else "\n"
          src + sep + "mcpServers:\n" + item
        } else {
          // The list block runs while lines stay indented (or blank).
          val blockEnd =
            lines.indexWhere(l => !l.startsWith(" ") && l.trim.nonEmpty, msIdx + 1) match {
              case -1 => lines.length
              case e  => e
            }
          val nameLine = s"- name: ${yamlString(serverName)}"
          val itemIdx = (msIdx + 1 until blockEnd).find(i => lines(i).trim == nameLine)
          itemIdx match {
            case Some(s0) =>
              // This item ends at the next sibling list item — a `- ` at the SAME indentation as
              // this one (deeper `- ` lines are the item's own `args:` sequence) — or the block end.
              val indent = leadingSpaces(lines(s0))
              val e = (s0 + 1 until blockEnd)
                .find(i => leadingSpaces(lines(i)) == indent && lines(i).trim.startsWith("- "))
                .getOrElse(blockEnd)
              (lines.take(s0) ++ item.split("\n", -1) ++ lines.drop(e)).mkString("\n")
            case None =>
              (lines.take(msIdx + 1) ++ item.split("\n", -1) ++ lines.drop(msIdx + 1))
                .mkString("\n")
          }
        }
      }
    withTrailingNewline(body)
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

  private[sbtplugin] def writeRulesAndSteer(client: String, baseDir: File, log: Logger): Unit = {
    // 1. Create SCALA_SEMANTIC_RULES.md if not exists
    val rulesFile = baseDir / "SCALA_SEMANTIC_RULES.md"
    val defaultRulesContent =
      """# Scala Semantic Rules
        |
        |For Scala source questions, use ScalaSemantic MCP tools before shell text tools. Preferably compile code before usage, then more ScalaSemantic functions can be used with better results.
        |
        |Do not use `cat`, `sed`, `rg`, or similar tools to inspect `.scala` files for symbol, type,
        |signature, hierarchy, implicit, reference, or call-path questions when ScalaSemantic tools are
        |available.
        |
        |Use shell for builds, tests, git, config, docs, scripts, and non-Scala text work.
        |""".stripMargin

    if (!rulesFile.exists()) {
      IO.write(rulesFile, defaultRulesContent)
      log.info(s"Created default rules file: $rulesFile")
    }

    // 2. Identify the LLM specific config file based on client
    val normalized = client.trim.toLowerCase.replace('_', '-')
    val optLlmConfig = normalized match {
      case "claude" | "claude-code" | "anthropic" =>
        Some(
          (baseDir / "CLAUDE.md", "CLAUDE.md", "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)")
        )
      case "gemini" | "google" | "google-gemini" | "gemini-cli" =>
        Some((baseDir / "AGENTS.md", "AGENTS.md", "@SCALA_SEMANTIC_RULES.md"))
      case "codex" | "openai" | "openai-codex" =>
        Some(
          (
            baseDir / ".cursorrules",
            ".cursorrules",
            "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)"
          )
        )
      case "cline" | "roo" | "roo-code" =>
        Some(
          (
            baseDir / ".clinerules",
            ".clinerules",
            "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)"
          )
        )
      case "continue" | "continue-dev" =>
        Some(
          (baseDir / ".continue" / "rules.txt", ".continue/rules.txt", "SCALA_SEMANTIC_RULES.md")
        )
      case _ =>
        None
    }

    optLlmConfig.foreach { case (file, name, reference) =>
      if (!file.exists()) {
        val content = if (name == "AGENTS.md") {
          s"""# AGENTS.md instructions
             |
             |<INSTRUCTIONS>
             |$reference
             |</INSTRUCTIONS>
             |""".stripMargin
        } else if (name.endsWith(".md") || name.endsWith("rules") || name.endsWith("rules.txt")) {
          s"""# Project Rules
             |
             |Please follow the rules in $reference for working with Scala code.
             |""".stripMargin
        } else {
          s"Please follow the rules in $reference for working with Scala code."
        }
        IO.write(file, content)
        log.info(s"Created LLM-specific rules file: $file pointing to SCALA_SEMANTIC_RULES.md")
      } else {
        // Read and update/append if reference not present
        val existingContent = IO.read(file)
        if (
          !existingContent
            .contains("SCALA_SEMANTIC_RULES.md") && !existingContent.contains("SCALA_CODE_RULES.md")
        ) {
          val separator = if (existingContent.endsWith("\n")) "" else "\n"
          val appendContent = if (name == "AGENTS.md") {
            s"""$separator
               |<INSTRUCTIONS>
               |$reference
               |</INSTRUCTIONS>
               |""".stripMargin
          } else {
            s"""$separator
               |## Scala Code Rules
               |Please follow the rules in $reference.
               |""".stripMargin
          }
          IO.write(file, existingContent + appendContent)
          log.info(s"Updated LLM-specific rules file: $file to point to SCALA_SEMANTIC_RULES.md")
        } else if (existingContent.contains("SCALA_CODE_RULES.md")) {
          // Replace legacy name with the new one
          val updated = existingContent
            .replace("SCALA_CODE_RULES.md", "SCALA_SEMANTIC_RULES.md")
            .replace("@SCALA_CODE_RULES.md", "@SCALA_SEMANTIC_RULES.md")
          IO.write(file, updated)
          log.info(
            s"Migrated legacy SCALA_CODE_RULES.md reference in: $file to SCALA_SEMANTIC_RULES.md"
          )
        }
      }
    }
  }
}
