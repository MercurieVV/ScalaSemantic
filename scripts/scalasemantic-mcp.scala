#!/usr/bin/env -S scala-cli shebang
//> using scala "3.8.4"
// Dependency lives in the script, not passed via --dependency: scala-cli/coursier resolve +
// cache the published artifact themselves, so this script needs no manual jar download/cache
// logic. "latest.release" is a coursier magic version that always re-resolves to the newest
// release; edit this line (or vendor a pinned copy of this script) to freeze a version.
//> using dep "io.github.mercurievv::scalasemantic-mcp:0.4.4"
// Silences the JDK's "sun.misc.Unsafe deprecated" stderr warning triggered by protobuf's reflective
// memory access (JEP 471/498) — cosmetic only, harmless either way since it's stderr, not the
// stdout JSON-RPC stream the MCP client actually reads.
//> using javaOpt "--sun-misc-unsafe-memory-access=allow"

import com.github.mercurievv.scalasemantic.mcpServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

object ScalaSemanticMcpScript:
  private val ServerName = "scala-semantic"
  private val RemoteScript =
    "https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.scala"
  // What generated client configs launch daily: a bare `scala-cli run --dependency ... --main-class
  // ...` invocation with no script file involved at all, so every server spawn is just a
  // coursier-cached jar load — no network fetch of this script and no recompile on each launch
  // (unlike pointing configs back at RemoteScript, which re-downloads + recompiles every time).
  private val ServerDependency = "io.github.mercurievv::scalasemantic-mcp:0.4.4"
  private val ServerMainClass = "com.github.mercurievv.scalasemantic.mcpServer"

  @main def main(rawArgs: String*): Unit =
    // `scala-cli <script> -- serve foo` strips the "--" separator itself; `./script.scala -- serve
    // foo` (shebang mode) does not, since the script path is already unambiguous there. Strip a
    // stray leading "--" ourselves so both invocation styles parse identically.
    val args = rawArgs.toList match
      case "--" :: rest => rest
      case other        => other

    args.headOption match
      case Some("setup" | "configure" | "install") =>
        setup(args.drop(1).toList)
      case Some("serve" | "run") =>
        serve(args.drop(1).toList)
      case Some("--help" | "-h" | "help") =>
        usage(0)
      case _ =>
        serve(args.toList)

  private final case class SetupOptions(
      project: Path = Path.of(".").toAbsolutePath.normalize(),
      client: String = "all",
      command: String = "scala-cli",
      skipSemanticdbConfig: Boolean = false,
      guard: Boolean = true
  )

  private def setup(rawArgs: List[String]): Unit =
    val opts = parseSetup(rawArgs)
    val project = opts.project
    Files.createDirectories(project)
    ensureSemanticdbConfig(project, opts.skipSemanticdbConfig)
    ensureRules(project, opts.client)
    writeClientConfigs(project, opts)
    if opts.guard then installGuardHook(project, opts.client)
    ensureClasspathMetadataDir(project)

  private def serve(rawArgs: List[String]): Unit =
    mcpServer(rawArgs*)

  private def parseSetup(args: List[String]): SetupOptions =
    @tailrec def loop(rest: List[String], opts: SetupOptions): SetupOptions =
      rest match
        case Nil                                       => opts
        case ("--project" | "--root") :: value :: tail =>
          loop(tail, opts.copy(project = Path.of(value).toAbsolutePath.normalize()))
        case ("--client" | "-c") :: value :: tail =>
          loop(tail, opts.copy(client = value))
        case "--command" :: value :: tail =>
          loop(tail, opts.copy(command = value))
        case "--skip-semanticdb-config" :: tail =>
          loop(tail, opts.copy(skipSemanticdbConfig = true))
        case "--no-guard" :: tail =>
          loop(tail, opts.copy(guard = false))
        case "--guard" :: tail =>
          loop(tail, opts.copy(guard = true))
        case ("--help" | "-h") :: _ =>
          usage(0)
        case bad :: _ =>
          err(s"unknown setup argument: $bad")
          usage(2)
    loop(args, SetupOptions())

  private def ensureSemanticdbConfig(project: Path, skip: Boolean): Unit =
    if skip then return
    val sbtFiles =
      Using.resource(Files.list(project)) { stream =>
        stream
          .iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".sbt"))
          .toVector
      }
    val hasBuildMill =
      Files.exists(project.resolve("build.mill")) || Files.exists(project.resolve("build.sc"))
    val hasProjectScala = Files.exists(project.resolve("project.scala"))
    val hasScalaFiles = hasSuffix(project, ".scala") || hasSuffix(project, ".sc")

    if sbtFiles.nonEmpty then
      val classpathConfigured =
        sbtFiles.exists(p => Files.readString(p).contains("scalaSemanticWriteClasspath"))
      if !classpathConfigured then
        val file = project.resolve("scala-semantic.sbt")
        Files.writeString(
          file,
          """|// Generated by ScalaSemantic MCP setup.
             |import sbt._
             |import Keys._
             |import java.nio.file.{Files, StandardCopyOption}
             |
             |ThisBuild / semanticdbEnabled := true
             |
             |lazy val scalaSemanticWriteClasspath =
             |  taskKey[File]("Write module-aware compile classpath metadata for ScalaSemantic MCP.")
             |lazy val scalaSemanticWriteModules =
             |  taskKey[File]("Write module-structure metadata for ScalaSemantic MCP.")
             |
             |def scalaSemanticJsonString(value: String): String =
             |  "\"" + value.flatMap {
             |    case '"'  => "\\\""
             |    case '\\' => "\\\\"
             |    case '\b' => "\\b"
             |    case '\f' => "\\f"
             |    case '\n' => "\\n"
             |    case '\r' => "\\r"
             |    case '\t' => "\\t"
             |    case c if c < ' ' => "\\u%04x".format(c.toInt)
             |    case c => c.toString
             |  } + "\""
             |
             |def scalaSemanticRel(root: File, file: File): String = {
             |  val rootPath = root.toPath.toAbsolutePath.normalize()
             |  val path = file.toPath.toAbsolutePath.normalize()
             |  if (path.startsWith(rootPath)) rootPath.relativize(path).toString else path.toString
             |}
             |
             |ThisBuild / scalaSemanticWriteClasspath := {
             |  (ThisBuild / scalaSemanticWriteModules).value
             |  val root = (ThisBuild / baseDirectory).value
             |  val ids = name.all(ScopeFilter(inAnyProject)).value
             |  val dirs = baseDirectory.all(ScopeFilter(inAnyProject)).value
             |  val versions = scalaVersion.all(ScopeFilter(inAnyProject)).value
             |  val cps = (Compile / fullClasspath).all(ScopeFilter(inAnyProject)).value
             |  val modules = ids.zip(dirs).zip(versions).zip(cps).map {
             |    case (((id, dir), version), cp) =>
             |      val classpath = cp.map(_.data).distinct.map { entry =>
             |        "        " + scalaSemanticJsonString(scalaSemanticRel(root, entry))
             |      }.mkString(",\n")
             |      "    {\n" +
             |        "      \"id\": " + scalaSemanticJsonString(id) + ",\n" +
             |        "      \"baseDir\": " + scalaSemanticJsonString(scalaSemanticRel(root, dir)) + ",\n" +
             |        "      \"scalaVersion\": " + scalaSemanticJsonString(version) + ",\n" +
             |        "      \"configuration\": \"Compile\",\n" +
             |        "      \"classpath\": [\n" +
             |        classpath + "\n" +
             |        "      ]\n" +
             |        "    }"
             |  }.mkString(",\n")
             |  val content =
             |    "{\n" +
             |      "  \"schemaVersion\": 1,\n" +
             |      "  \"buildTool\": \"sbt\",\n" +
             |      "  \"modules\": [\n" +
             |      modules + "\n" +
             |      "  ]\n" +
             |      "}\n"
             |  val out = root / ".scala-semantic" / "classpath-sbt.json"
             |  IO.createDirectory(out.getParentFile)
             |  val current = if (out.isFile) IO.read(out) else ""
             |  if (current != content) {
             |    val tmp = out.getParentFile / (out.getName + ".tmp")
             |    IO.write(tmp, content)
             |    Files.move(tmp.toPath, out.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
             |  }
             |  out
             |}
             |
             |ThisBuild / scalaSemanticWriteModules := {
             |  val root = (ThisBuild / baseDirectory).value
             |  val ids = name.all(ScopeFilter(inAnyProject)).value
             |  val dirs = baseDirectory.all(ScopeFilter(inAnyProject)).value
             |  val outDirs = (Compile / classDirectory).all(ScopeFilter(inAnyProject)).value.map(_.getParentFile.getParentFile)
             |  val modules = ids.zip(dirs).zip(outDirs).map {
             |    case ((id, dir), outDir) =>
             |      "    {\n" +
             |        "      \"name\": " + scalaSemanticJsonString(id) + ",\n" +
             |        "      \"path_from_root\": " + scalaSemanticJsonString(scalaSemanticRel(root, dir)) + ",\n" +
             |        "      \"path_to_out_dir\": " + scalaSemanticJsonString(scalaSemanticRel(root, outDir)) + "\n" +
             |        "    }"
             |  }.mkString(",\n")
             |  val content =
             |    "{\n" +
             |      "  \"schemaVersion\": 1,\n" +
             |      "  \"buildTool\": \"sbt\",\n" +
             |      "  \"parent\": {\n" +
             |      "    \"name\": \"root\",\n" +
             |      "    \"path_from_root\": \".\",\n" +
             |      "    \"path_to_out_dir\": \"target\"\n" +
             |      "  },\n" +
             |      "  \"modules\": [\n" +
             |      modules + "\n" +
             |      "  ]\n" +
             |      "}\n"
             |  val out = root / ".scala-semantic" / "modules-sbt.json"
             |  IO.createDirectory(out.getParentFile)
             |  val current = if (out.isFile) IO.read(out) else ""
             |  if (current != content) {
             |    val tmp = out.getParentFile / (out.getName + ".tmp")
             |    IO.write(tmp, content)
             |    Files.move(tmp.toPath, out.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
             |  }
             |  out
             |}
             |
             |Global / onLoad := {
             |  val prev = (Global / onLoad).value
             |  prev.andThen { state =>
             |    val key = AttributeKey[Boolean]("scalaSemanticClasspathWritten")
             |    if (state.get(key).getOrElse(false)) state
             |    else {
             |      val newState = state.put(key, true)
             |      "scalaSemanticWriteModules" :: "scalaSemanticWriteClasspath" :: newState
             |    }
             |  }
             |}
             |""".stripMargin
        )
        err(s"created $file")
      else
        val alreadyConfigured =
          sbtFiles.exists(p => Files.readString(p).contains("semanticdbEnabled"))
        if !alreadyConfigured then
          val file = project.resolve("scala-semantic.sbt")
          val existing = if Files.exists(file) then Files.readString(file) else ""
          Files.writeString(file, existing + "\nThisBuild / semanticdbEnabled := true\n")
          err(s"updated $file")
    else if !hasBuildMill && (hasProjectScala || hasScalaFiles) then
      ensureSemanticdbScalacli(project)
      writeScalaCliClasspath(project, "scala-cli")
    else if hasBuildMill then
      err(
        "scalasemantic-mcp: Mill project detected. Setup will generate MCP configurations, but make sure to run 'mill scalaSemanticWriteClasspath' or let compileClasspath run in build.mill."
      )
    else err("no build files found; enable SemanticDB manually before using ScalaSemantic")

  private def ensureSemanticdbScalacli(project: Path): Unit =
    val files =
      Using.resource(Files.list(project)) { stream =>
        stream
          .iterator()
          .asScala
          .filter(p =>
            Files.isRegularFile(
              p
            ) && (p.getFileName.toString == "project.scala" || p.getFileName.toString
              .endsWith(".scala") || p.getFileName.toString.endsWith(".sc"))
          )
          .toVector
      }
    val alreadyConfigured =
      files.exists(p => {
        val s = Files.readString(p)
        s.contains("semanticdb") || s.contains("Ysemanticdb")
      })
    if !alreadyConfigured then
      val file = project.resolve("project.scala")
      if !Files.exists(file) then
        var is213 = false
        for f <- files do
          val s = Files.readString(f)
          if s.contains("using scala \"2.") then is213 = true
        val content =
          if is213 then """|// Generated by ScalaSemantic MCP setup.
               |//> using plugin "org.scalameta:::semanticdb-scalac:4.13.9"
               |//> using options "-Yrangepos" "-P:semanticdb:sourceroot:."
               |""".stripMargin
          else """|// Generated by ScalaSemantic MCP setup.
               |//> using options "-Ysemanticdb" "-sourceroot" "."
               |""".stripMargin
        Files.writeString(file, content)
        err(s"created $file")

  private def writeScalaCliClasspath(project: Path, command: String): Unit =
    try
      val cmd = Seq(command, "compile", "--print-class-path", project.toString)
      val classpathString = scala.sys.process.Process(cmd, project.toFile).!!
      val entries = classpathString
        .split(java.io.File.pathSeparator)
        .map(_.trim)
        .filter(_.nonEmpty)
        .distinct
        .toVector
      val scalaVersion = entries
        .flatMap { entry =>
          val filename = Path.of(entry).getFileName.toString
          if filename.startsWith("scala3-library_3-") then
            val parts = filename.stripPrefix("scala3-library_3-").stripSuffix(".jar").split("-")
            if parts.length >= 1 then Some(parts(0)) else None
          else if filename.startsWith("scala-library-") then
            Some(filename.stripPrefix("scala-library-").stripSuffix(".jar"))
          else None
        }
        .headOption
        .getOrElse("3.8.4")

      val cpEntries = entries
        .map(e => "        " + scalaSemanticJsonString(scalaSemanticRel(project, Path.of(e))))
        .mkString(",\n")
      val content =
        "{\n" +
          "  \"schemaVersion\": 1,\n" +
          "  \"buildTool\": \"scala-cli\",\n" +
          "  \"modules\": [\n" +
          "    {\n" +
          "      \"id\": \"root\",\n" +
          "      \"baseDir\": \".\",\n" +
          "      \"scalaVersion\": " + scalaSemanticJsonString(scalaVersion) + ",\n" +
          "      \"configuration\": \"Compile\",\n" +
          "      \"classpath\": [\n" +
          cpEntries + "\n" +
          "      ]\n" +
          "    }\n" +
          "  ]\n" +
          "}\n"

      val outDir = project.resolve(".scala-semantic")
      Files.createDirectories(outDir)
      val out = outDir.resolve("classpath-scala-cli.json")
      Files.writeString(out, content)
      err(s"generated Scala CLI classpath metadata: $out")
    catch
      case e: Exception =>
        err(s"warning: failed to run '$command compile --print-class-path': ${e.getMessage}")

  private def scalaSemanticJsonString(value: String): String =
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

  private def scalaSemanticRel(root: Path, file: Path): String =
    val rootPath = root.toAbsolutePath.normalize()
    val path = file.toAbsolutePath.normalize()
    if path.startsWith(rootPath) then rootPath.relativize(path).toString else path.toString

  private def ensureRules(project: Path, client: String): Unit =
    val rulesFile = project.resolve("SCALA_SEMANTIC_RULES.md")
    if !Files.exists(rulesFile) then
      Files.writeString(
        rulesFile,
        """|# Scala Semantic Rules
           |
           |For Scala source questions, use ScalaSemantic MCP tools before shell text tools. Preferably compile code before usage, then more ScalaSemantic functions can be used with better results.
           |
           |Do not use `cat`, `sed`, `rg`, or similar tools to inspect `.scala` files for symbol, type, signature, hierarchy, implicit, reference, or call-path questions when ScalaSemantic tools are available.
           |
           |Use shell for builds, tests, git, config, docs, scripts, and non-Scala text work.
           |
           |In Claude Code this rule is enforced, not merely advised: the `.claude/hooks/scala-semantic-guard.sh` PreToolUse hook denies Read/Grep/Glob and shell text tools that target `.scala` files. If the semantic tools genuinely cannot answer, re-run the command through Bash with a trailing `# semantic-fallback: <reason>` marker — allowed, and logged to `.claude/semantic-fallback.log`.
           |""".stripMargin
      )
      err(s"created $rulesFile")

    val clients =
      if client.trim.toLowerCase == "all" then
        Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity")
      else Seq(client)
    clients.foreach(c => writeSteerFile(project, c))

  private def writeSteerFile(project: Path, client: String): Unit =
    val normalized = client.trim.toLowerCase.replace('_', '-')
    val target =
      normalized match
        case "claude" | "claude-code" | "anthropic" =>
          Some(project.resolve("CLAUDE.md") -> "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)")
        case "gemini" | "google" | "google-gemini" | "gemini-cli" | "antigravity" |
            "antigravity-cli" | "agy" =>
          Some(project.resolve("AGENTS.md") -> "@SCALA_SEMANTIC_RULES.md")
        case "codex" | "openai" | "openai-codex" =>
          Some(
            project.resolve(".cursorrules") -> "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)"
          )
        case "cline" | "roo" | "roo-code" =>
          Some(
            project.resolve(".clinerules") -> "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)"
          )
        case "continue" | "continue-dev" =>
          Some(project.resolve(".continue").resolve("rules.txt") -> "SCALA_SEMANTIC_RULES.md")
        case _ => None

    target.foreach { case (file, reference) =>
      if Files.exists(file) then
        val current = Files.readString(file)
        if current.contains("SCALA_CODE_RULES.md") then
          Files.writeString(file, current.replace("SCALA_CODE_RULES.md", "SCALA_SEMANTIC_RULES.md"))
          err(s"updated $file")
        else if current.contains("Please follow the rules in") then
          Files.writeString(
            file,
            current
              .replace(
                s"Please follow the rules in $reference.",
                s"MUST follow the rules in $reference. Mandatory, not optional."
              )
              .replace(
                s"Please follow the rules in $reference for working with Scala code.",
                s"MUST follow the rules in $reference for working with Scala code. Mandatory, not optional."
              )
          )
          err(s"updated $file")
        else if !current.contains("SCALA_SEMANTIC_RULES.md") then
          val sep = if current.endsWith("\n") then "" else "\n"
          Files.writeString(
            file,
            current + sep + s"\n## Scala Code Rules\nMUST follow the rules in $reference. Mandatory, not optional.\n"
          )
          err(s"updated $file")
      else
        Files.createDirectories(file.getParent)
        val content =
          if file.getFileName.toString == "AGENTS.md" then s"""|# AGENTS.md instructions
                |
                |<INSTRUCTIONS>
                |$reference
                |</INSTRUCTIONS>
                |""".stripMargin
          else
            s"# Project Rules\n\nMUST follow the rules in $reference for working with Scala code. Mandatory, not optional.\n"
        Files.writeString(file, content)
        err(s"created $file")
    }

  private final case class Target(relPath: String, fmt: Fmt, extraJson: Seq[(String, String)])
  private sealed trait Fmt
  private case object JsonFmt extends Fmt
  private case object TomlFmt extends Fmt
  private case object YamlFmt extends Fmt

  // --- Claude Code guard hook -------------------------------------------------------------
  // Kept in sync with launcher/.../LauncherGuardHook.scala (the jar-side implementation this
  // standalone scala-cli script mirrors).
  private val GuardHookRelPath = ".claude/hooks/scala-semantic-guard.sh"
  private val GuardSettingsRelPath = ".claude/settings.json"
  // Present in both the hook script and the settings entry, so "already installed" is a plain
  // substring check on either file — no JSON parsing needed for idempotency.
  private val GuardMarker = "scala-semantic-guard"
  private val GuardHookCommand = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/scala-semantic-guard.sh"
  private val GuardMatcher = "Read|Grep|Glob|Bash"

  private def installGuardHook(project: Path, client: String): Unit =
    if guardClaudeSelected(client) then
      val hook = project.resolve(GuardHookRelPath)
      Files.createDirectories(hook.getParent)
      val existing = if Files.exists(hook) then Some(Files.readString(hook)) else None
      if !existing.contains(guardScript) then
        Files.writeString(hook, guardScript)
        err(s"${if existing.isEmpty then "created" else "updated"} $hook")
      makeExecutable(hook)

      val settings = project.resolve(GuardSettingsRelPath)
      val current = if Files.exists(settings) then Some(Files.readString(settings)) else None
      mergeGuardSettings(current) match
        case Some(merged) =>
          Files.writeString(settings, merged)
          err(s"registered guard hook in $settings")
        case None => ()

  private def guardClaudeSelected(client: String): Boolean =
    client.trim.toLowerCase.replace('_', '-') match
      case "all" | "claude" | "claude-code" | "anthropic" => true
      case _                                              => false

  private def makeExecutable(file: Path): Unit =
    Try {
      val perms = Files.getPosixFilePermissions(file).asScala.toSet
      Files.setPosixFilePermissions(
        file,
        (perms ++ Set(
          PosixFilePermission.OWNER_EXECUTE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_EXECUTE
        )).asJava
      )
      // Windows / non-POSIX filesystems have no executable bit; the hook still runs via `sh`.
    }.getOrElse(())

  /** Splice the guard entry into `.claude/settings.json`, preserving everything already there.
    *
    * Returns [[None]] when the file already registers the hook, so a re-run of `setup` neither
    * rewrites the file nor appends a duplicate entry.
    */
  private def mergeGuardSettings(existing: Option[String]): Option[String] =
    val src = existing.getOrElse("")
    if src.contains(GuardMarker) then None
    else if src.trim.isEmpty then Some(freshGuardSettings)
    else
      val rootOpen = src.indexOf('{')
      val rootClose = if rootOpen < 0 then -1 else matchBracket(src, rootOpen)
      if rootClose < 0 then Some(freshGuardSettings)
      else
        val hooksKey = findJsonKey(src, rootOpen + 1, rootClose, "hooks")
        if hooksKey < 0 then
          val hadEntries = src.substring(rootOpen + 1, rootClose).trim.nonEmpty
          val block = s"\n  \"hooks\": {\n    \"PreToolUse\": [\n$guardEntry\n    ]\n  }"
          val comma = if hadEntries then "," else ""
          Some(src.substring(0, rootOpen + 1) + block + comma + src.substring(rootOpen + 1))
        else
          val hooksOpen = src.indexOf('{', src.indexOf(':', hooksKey))
          val hooksClose = if hooksOpen < 0 then -1 else matchBracket(src, hooksOpen)
          if hooksClose < 0 then None
          else
            val preKey = findJsonKey(src, hooksOpen + 1, hooksClose, "PreToolUse")
            if preKey < 0 then
              val hadEntries = src.substring(hooksOpen + 1, hooksClose).trim.nonEmpty
              val ins =
                s"\n    \"PreToolUse\": [\n$guardEntry\n    ]${if hadEntries then "," else ""}"
              Some(src.substring(0, hooksOpen + 1) + ins + src.substring(hooksOpen + 1))
            else
              val arrOpen = src.indexOf('[', src.indexOf(':', preKey))
              val arrClose = if arrOpen < 0 then -1 else matchBracket(src, arrOpen)
              if arrClose < 0 then None
              else
                val hadEntries = src.substring(arrOpen + 1, arrClose).trim.nonEmpty
                val ins = s"\n$guardEntry${if hadEntries then "," else ""}"
                Some(src.substring(0, arrOpen + 1) + ins + src.substring(arrOpen + 1))

  private def guardEntry: String =
    s"""|      {
        |        "matcher": "$GuardMatcher",
        |        "hooks": [
        |          { "type": "command", "command": "${GuardHookCommand.replace("\"", "\\\"")}" }
        |        ]
        |      }""".stripMargin

  private def freshGuardSettings: String =
    s"""|{
        |  "hooks": {
        |    "PreToolUse": [
        |$guardEntry
        |    ]
        |  }
        |}
        |""".stripMargin

  /** The hook body: POSIX `sh`, no hard dependency beyond `jq` *or* `python3`.
    *
    * Fails open everywhere it is unsure (no JSON reader, no SemanticDB index, MCP server not
    * configured for this project) — a guard that blocks work it cannot justify would be removed
    * within a day, which protects nothing.
    */
  private val guardScript: String =
    """|#!/bin/sh
       |# Generated by ScalaSemantic MCP setup -- do not edit; re-run `scalasemantic-mcp setup`
       |# to regenerate, or `scalasemantic-mcp setup --no-guard` to stop installing it (then drop
       |# the PreToolUse entry from .claude/settings.json).
       |#
       |# Claude Code PreToolUse hook. Denies text-scraping tools on .scala sources so symbol
       |# questions go to the ScalaSemantic MCP tools, which answer from compiler facts at a
       |# fraction of the tokens and without missing renames/implicits/inferred uses.
       |#
       |# Exit codes: 0 = allow, 2 = deny (stderr is fed back to the agent).
       |
       |set -u
       |
       |root="${CLAUDE_PROJECT_DIR:-$PWD}"
       |payload=$(cat)
       |
       |# --- no JSON reader: fail open ---------------------------------------------------------
       |if command -v jq >/dev/null 2>&1; then
       |  reader=jq
       |elif command -v python3 >/dev/null 2>&1; then
       |  reader=python3
       |else
       |  exit 0
       |fi
       |
       |# tool name, then the tool_input fields that can name a Scala target, one per line.
       |if [ "$reader" = jq ]; then
       |  fields=$(printf '%s' "$payload" | jq -r '
       |    [ (.tool_name // ""),
       |      (.tool_input.file_path // ""),
       |      (.tool_input.glob // ""),
       |      (.tool_input.path // ""),
       |      (.tool_input.type // ""),
       |      (.tool_input.command // "") ]
       |    | .[] | tostring | gsub("\n"; " ")' 2>/dev/null) || exit 0
       |else
       |  fields=$(printf '%s' "$payload" | python3 -c '
       |import sys, json
       |try:
       |    d = json.load(sys.stdin)
       |except Exception:
       |    sys.exit(0)
       |i = d.get("tool_input") or {}
       |keys = ["file_path", "glob", "path", "type", "command"]
       |out = [d.get("tool_name", "")] + [i.get(k, "") for k in keys]
       |print("\n".join(str(x).replace("\n", " ") for x in out))
       |' 2>/dev/null) || exit 0
       |fi
       |
       |[ -n "$fields" ] || exit 0
       |tool=$(printf '%s\n' "$fields" | sed -n 1p)
       |file_path=$(printf '%s\n' "$fields" | sed -n 2p)
       |glob=$(printf '%s\n' "$fields" | sed -n 3p)
       |path=$(printf '%s\n' "$fields" | sed -n 4p)
       |ftype=$(printf '%s\n' "$fields" | sed -n 5p)
       |command_line=$(printf '%s\n' "$fields" | sed -n 6p)
       |
       |# --- explicit human/agent override -----------------------------------------------------
       |# `rg foo *.scala   # semantic-fallback: <reason>` is always allowed, and logged so the
       |# override stays auditable instead of silent.
       |case "$command_line" in
       |  *semantic-fallback:*)
       |    printf '%s\t%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$command_line" \
       |      >>"$root/.claude/semantic-fallback.log" 2>/dev/null
       |    exit 0
       |    ;;
       |esac
       |
       |# --- does this call target Scala sources? ----------------------------------------------
       |targets_scala=0
       |case "$tool" in
       |  Read)
       |    case "$file_path" in
       |      *.scala | *.sc) targets_scala=1 ;;
       |    esac
       |    ;;
       |  Grep | Glob)
       |    # Only when the call itself names Scala: an unscoped repo-wide search may legitimately
       |    # be after comments, config or non-Scala files.
       |    case "$glob$path$ftype" in
       |      *scala*) targets_scala=1 ;;
       |    esac
       |    ;;
       |  Bash)
       |    case "$command_line" in
       |      *.scala*)
       |        if printf '%s' "$command_line" | grep -Eq \
       |          '(^|[|;&(`]|[[:space:]])(grep|rg|ag|ack|cat|sed|awk|head|tail|less|more|nl)([[:space:]]|$)'
       |        then
       |          targets_scala=1
       |        fi
       |        ;;
       |    esac
       |    ;;
       |esac
       |[ "$targets_scala" = 1 ] || exit 0
       |
       |# --- fail open when the semantic answer is not actually available ----------------------
       |# No MCP server wired into this project: nothing better to route the agent to.
       |for cfg in "$root/.mcp.json" "$root/.claude/settings.json" "$root/.claude/settings.local.json"; do
       |  [ -f "$cfg" ] && grep -q 'scala-semantic' "$cfg" 2>/dev/null && configured=1
       |done
       |[ "${configured:-0}" = 1 ] || exit 0
       |
       |# No SemanticDB emitted yet (never compiled, or a non-Scala project): the MCP tools would
       |# return an empty index, so text search is the only thing that can work.
       |index=$(find "$root" \
       |  \( -name .git -o -name out -o -name target -o -name node_modules -o -name .scala-build \
       |     -o -name .worktrees -o -name website \) -prune -o \
       |  -name '*.semanticdb' -print 2>/dev/null | head -n 1)
       |[ -n "$index" ] || exit 0
       |
       |# --- deny ------------------------------------------------------------------------------
       |cat >&2 <<'MSG'
       |BLOCKED by ScalaSemantic guard: text tools are not allowed on .scala sources here.
       |Text search misses renames, re-exports, implicits and inferred uses, and over-matches
       |comments and same-named identifiers. Use the mcp__scala-semantic__* tools instead and
       |pick whichever fits the actual question:
       |  symbols / references / types  -> find_symbol, find_usages, type_at_position
       |  hierarchy / members / givens  -> class_hierarchy, members, resolve_implicits
       |  signatures / overloads        -> method_signature, find_overloads
       |  file or project shape         -> document_outline, structure, symbol_source
       |  literals, comments, TODOs     -> search_text
       |Stale or missing index: run the project's compile task, then refresh_workspace.
       |If the semantic tools genuinely cannot answer this, re-run the command through Bash with
       |a trailing `# semantic-fallback: <reason>` marker (allowed, and logged).
       |MSG
       |exit 2
       |""".stripMargin

  private def targetFor(client: String): Target =
    client.trim.toLowerCase.replace('_', '-') match
      case "codex" | "openai" | "openai-codex" =>
        Target(".codex/config.toml", TomlFmt, Nil)
      case "claude" | "claude-code" | "anthropic" =>
        Target(".mcp.json", JsonFmt, Nil)
      case "gemini" | "google" | "google-gemini" | "gemini-cli" =>
        Target(".gemini/settings.json", JsonFmt, Seq("timeout" -> "60000"))
      case "antigravity" | "antigravity-cli" | "agy" =>
        Target(".agents/mcp_config.json", JsonFmt, Nil)
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
        throw new IllegalArgumentException(
          s"unsupported client '$other'; use claude, codex, gemini, cline, roo, continue, antigravity, generic-json, or all"
        )

  private def writeClientConfigs(project: Path, opts: SetupOptions): Unit =
    val argv = Seq(
      opts.command,
      "run",
      "--dependency",
      ServerDependency,
      "--main-class",
      ServerMainClass,
      "--",
      "."
    )
    val clients =
      if opts.client.trim.toLowerCase == "all" then
        Seq("claude", "codex", "gemini", "cline", "roo", "continue", "antigravity")
      else Seq(opts.client)
    clients.foreach { client =>
      val target = targetFor(client)
      val out = project.resolve(target.relPath)
      Files.createDirectories(out.getParent)
      val existing = if Files.exists(out) then Some(Files.readString(out)) else None
      val merged =
        target.fmt match
          case JsonFmt => mergeJson(existing, ServerName, argv, target.extraJson)
          case TomlFmt => mergeToml(existing, ServerName, argv)
          case YamlFmt => mergeYaml(existing, ServerName, argv)
      Files.writeString(out, merged)
      err(s"wrote $out")
    }

  private def ensureClasspathMetadataDir(project: Path): Unit =
    Files.createDirectories(project.resolve(".scala-semantic"))

  private def hasSbt(project: Path): Boolean =
    hasSuffix(project, ".sbt")

  private def hasSuffix(project: Path, suffix: String): Boolean =
    Using.resource(Files.list(project)) { stream =>
      stream
        .iterator()
        .asScala
        .exists(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(suffix))
    }

  private def jsonEntry(argv: Seq[String], extraFields: Seq[(String, String)]): String =
    val (command, args) = splitArgv(argv)
    val argsJson = args.map(jsonString).mkString("[", ", ", "]")
    val extra = extraFields.map { case (name, value) =>
      s",\n      ${jsonString(name)}: $value"
    }.mkString
    s"""|{
        |      "command": ${jsonString(command)},
        |      "args": $argsJson$extra
        |    }""".stripMargin

  private def renderMcpJson(
      serverName: String,
      argv: Seq[String],
      extraFields: Seq[(String, String)]
  ): String =
    s"""|{
        |  "mcpServers": {
        |    ${jsonString(serverName)}: ${jsonEntry(argv, extraFields)}
        |  }
        |}
        |""".stripMargin

  private def mergeJson(
      existing: Option[String],
      serverName: String,
      argv: Seq[String],
      extraFields: Seq[(String, String)]
  ): String =
    val fresh = renderMcpJson(serverName, argv, extraFields)
    val src = existing.getOrElse("")
    val rootOpen = if src.trim.isEmpty then -1 else src.indexOf('{')
    val rootClose = if rootOpen < 0 then -1 else matchBracket(src, rootOpen)
    if rootClose < 0 then fresh
    else
      val entry = jsonEntry(argv, extraFields)
      val msKey = findJsonKey(src, rootOpen + 1, rootClose, "mcpServers")
      if msKey < 0 then
        val hadEntries = src.substring(rootOpen + 1, rootClose).trim.nonEmpty
        val block = s"""\n  "mcpServers": {\n    ${jsonString(serverName)}: $entry\n  }"""
        val comma = if hadEntries then "," else ""
        src.substring(0, rootOpen + 1) + block + comma + src.substring(rootOpen + 1)
      else
        val objOpen = src.indexOf('{', src.indexOf(':', msKey))
        val objClose = matchBracket(src, objOpen)
        val snKey = findJsonKey(src, objOpen + 1, objClose, serverName)
        if snKey >= 0 then
          val vs = skipWs(src, src.indexOf(':', snKey) + 1, objClose)
          val ve = jsonValueEnd(src, vs, objClose)
          src.substring(0, vs) + entry + src.substring(ve)
        else
          val hadEntries = src.substring(objOpen + 1, objClose).trim.nonEmpty
          val ins = s"""\n    ${jsonString(serverName)}: $entry${if hadEntries then "," else ""}"""
          src.substring(0, objOpen + 1) + ins + src.substring(objOpen + 1)

  private def renderCodexToml(serverName: String, argv: Seq[String]): String =
    val (command, args) = splitArgv(argv)
    val argsToml = args.map(tomlString).mkString("[", ", ", "]")
    s"""|[mcp_servers.${tomlKey(serverName)}]
        |command = ${tomlString(command)}
        |args = $argsToml
        |startup_timeout_sec = 60
        |tool_timeout_sec = 60""".stripMargin

  private def mergeToml(existing: Option[String], serverName: String, argv: Seq[String]): String =
    val fresh = renderCodexToml(serverName, argv)
    val src = existing.getOrElse("")
    val body =
      if src.trim.isEmpty then fresh
      else
        val header = s"[mcp_servers.${tomlKey(serverName)}]"
        val lines = src.split("\n", -1).toVector
        val idx = lines.indexWhere(_.trim == header)
        if idx < 0 then
          val sep = if src.endsWith("\n") then "" else "\n"
          src + sep + "\n" + fresh
        else
          val end = lines.indexWhere(_.trim.startsWith("["), idx + 1) match
            case -1 => lines.length
            case e  => e
          (lines.take(idx) ++ fresh.split("\n", -1) ++ lines.drop(end)).mkString("\n")
    withTrailingNewline(body)

  private def continueItem(serverName: String, argv: Seq[String]): String =
    val (command, argvArgs) = splitArgv(argv)
    val args =
      if argvArgs.isEmpty then ""
      else argvArgs.map(a => s"\n      - ${yamlString(a)}").mkString("\n    args:", "", "")
    s"""|  - name: ${yamlString(serverName)}
        |    command: ${yamlString(command)}$args
        |    connectionTimeout: 60000""".stripMargin

  private def renderContinueYaml(serverName: String, argv: Seq[String]): String =
    s"""|name: ScalaSemantic MCP
        |version: 1.0.0
        |schema: v1
        |mcpServers:
        |${continueItem(serverName, argv)}
        |""".stripMargin

  private def mergeYaml(existing: Option[String], serverName: String, argv: Seq[String]): String =
    val fresh = renderContinueYaml(serverName, argv)
    val src = existing.getOrElse("")
    val body =
      if src.trim.isEmpty then fresh
      else
        val item = continueItem(serverName, argv)
        val lines = src.split("\n", -1).toVector
        val msIdx = lines.indexWhere(_.matches("""mcpServers:\s*"""))
        if msIdx < 0 then
          val sep = if src.endsWith("\n") then "" else "\n"
          src + sep + "mcpServers:\n" + item
        else
          val blockEnd =
            lines.indexWhere(l => !l.startsWith(" ") && l.trim.nonEmpty, msIdx + 1) match
              case -1 => lines.length
              case e  => e
          val nameLine = s"- name: ${yamlString(serverName)}"
          val itemIdx =
            (msIdx + 1).until(blockEnd).find(i => lines.lift(i).exists(_.trim == nameLine))
          itemIdx match
            case Some(s0) =>
              val indent = lines.lift(s0).fold(0)(leadingSpaces)
              val e = (s0 + 1)
                .until(blockEnd)
                .find(i =>
                  lines
                    .lift(i)
                    .exists(line => leadingSpaces(line) == indent && line.trim.startsWith("- "))
                )
                .getOrElse(blockEnd)
              (lines.take(s0) ++ item.split("\n", -1) ++ lines.drop(e)).mkString("\n")
            case None =>
              (lines.take(msIdx + 1) ++ item.split("\n", -1) ++ lines.drop(msIdx + 1))
                .mkString("\n")
    withTrailingNewline(body)

  @tailrec private def skipWs(s: String, i: Int, limit: Int): Int =
    if i < limit && s.charAt(i).isWhitespace then skipWs(s, i + 1, limit) else i

  private def withTrailingNewline(s: String): String =
    s.replaceAll("\\R+\\z", "") + "\n"

  private def leadingSpaces(line: String): Int = line.takeWhile(_ == ' ').length

  private def splitArgv(argv: Seq[String]): (String, Seq[String]) =
    argv match
      case command +: args => command -> args
      case _               => "" -> Seq.empty

  private def jsonValueEnd(s: String, vs: Int, limit: Int): Int =
    s.charAt(vs) match
      case '{' | '[' => matchBracket(s, vs) + 1
      case '"'       =>
        @tailrec def str(i: Int, esc: Boolean): Int =
          if i >= limit then limit
          else
            val c = s.charAt(i)
            if esc then str(i + 1, esc = false)
            else if c == '\\' then str(i + 1, esc = true)
            else if c == '"' then i + 1
            else str(i + 1, esc = false)
        str(vs + 1, esc = false)
      case _ =>
        @tailrec def scan(i: Int): Int =
          if i < limit && s.charAt(i) != ',' && s.charAt(i) != '}' then scan(i + 1) else i
        @tailrec def back(i: Int): Int =
          if i > vs && s.charAt(i - 1).isWhitespace then back(i - 1) else i
        back(scan(vs))

  private def matchBracket(s: String, openIdx: Int): Int =
    @tailrec def loop(i: Int, depth: Int, inStr: Boolean, esc: Boolean): Int =
      if i >= s.length then -1
      else
        val c = s.charAt(i)
        if inStr then
          if esc then loop(i + 1, depth, inStr = true, esc = false)
          else if c == '\\' then loop(i + 1, depth, inStr = true, esc = true)
          else if c == '"' then loop(i + 1, depth, inStr = false, esc = false)
          else loop(i + 1, depth, inStr = true, esc = false)
        else
          c match
            case '"'       => loop(i + 1, depth, inStr = true, esc = false)
            case '{' | '[' => loop(i + 1, depth + 1, inStr = false, esc = false)
            case '}' | ']' =>
              if depth - 1 == 0 then i else loop(i + 1, depth - 1, inStr = false, esc = false)
            case _ => loop(i + 1, depth, inStr = false, esc = false)
    loop(openIdx, 0, inStr = false, esc = false)

  private def colonFollows(s: String, from: Int, end: Int): Boolean =
    val j = skipWs(s, from, end)
    j < end && s.charAt(j) == ':'

  private def findJsonKey(s: String, start: Int, end: Int, key: String): Int =
    val target = "\"" + key + "\""
    @tailrec def loop(i: Int, depth: Int, inStr: Boolean, esc: Boolean): Int =
      if i >= end then -1
      else
        val c = s.charAt(i)
        if inStr then
          if esc then loop(i + 1, depth, inStr = true, esc = false)
          else if c == '\\' then loop(i + 1, depth, inStr = true, esc = true)
          else if c == '"' then loop(i + 1, depth, inStr = false, esc = false)
          else loop(i + 1, depth, inStr = true, esc = false)
        else
          c match
            case '"' =>
              if depth == 0 && s.regionMatches(i, target, 0, target.length) && colonFollows(
                  s,
                  i + target.length,
                  end
                )
              then i
              else loop(i + 1, depth, inStr = true, esc = false)
            case '{' | '[' => loop(i + 1, depth + 1, inStr = false, esc = false)
            case '}' | ']' => loop(i + 1, depth - 1, inStr = false, esc = false)
            case _         => loop(i + 1, depth, inStr = false, esc = false)
    loop(start, 0, inStr = false, esc = false)

  private def tomlKey(value: String): String =
    if value.matches("[A-Za-z0-9_-]+") then value else tomlString(value)

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

  private def usage(exit: Int): Nothing =
    Console.err.println(
      s"""|Usage:
          |  scala-cli $RemoteScript -- setup [--client claude|codex|gemini|cline|roo|continue|antigravity|all] [--project DIR]
          |  scala-cli $RemoteScript -- serve <semanticdb-root> [classpath-file] [--log] [--log-output]
          |
          |Setup writes MCP client config that launches the server directly (no script involved, so
          |every launch is just a coursier-cached jar load, not a re-download + recompile of this
          |script):
          |  command = scala-cli
          |  args    = [run, --dependency, $ServerDependency, --main-class, $ServerMainClass, --, .]
          |
          |To pin a version instead of latest.release, re-run setup after editing ServerDependency in
          |a local copy of this script.
          |""".stripMargin
    )
    sys.exit(exit)

  private def err(message: String): Unit =
    Console.err.println(s"scalasemantic-mcp: $message")
