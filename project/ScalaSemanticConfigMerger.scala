package com.github.mercurievv.scalasemantic.sbtplugin

import sbt.*

import scala.annotation.tailrec

object ScalaSemanticConfigMerger {

  sealed trait Fmt
  case object JsonFmt extends Fmt
  case object TomlFmt extends Fmt
  case object YamlFmt extends Fmt

  final case class Target(relPath: String, fmt: Fmt, extraJson: Seq[(String, String)])

  def targetFor(client: String): Target = {
    val normalized = client.trim.toLowerCase.replace('_', '-')
    normalized match {
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
        sys.error(
          s"Unsupported mcpClient '$other'. Use one of: " +
            "claude, codex, gemini, cline, roo, continue, antigravity, generic-json."
        )
    }
  }

  // --- JSON -------------------------------------------------------------------------------------

  private def jsonEntry(argv: Seq[String], extraFields: Seq[(String, String)]): String = {
    val (command, args) = splitArgv(argv)
    val argsJson = args.map(jsonString).mkString("[", ", ", "]")
    val extra =
      extraFields.map { case (name, value) => s",\n      ${jsonString(name)}: $value" }.mkString
    s"""|{
        |      "command": ${jsonString(command)},
        |      "args": $argsJson$extra
        |    }""".stripMargin
  }

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

  def mergeJson(
      existing: Option[String],
      serverName: String,
      argv: Seq[String],
      extraFields: Seq[(String, String)]
  ): String = {
    val fresh = renderMcpJson(serverName, argv, extraFields)
    val src = existing.getOrElse("")
    val rootOpen = if (src.trim.isEmpty) -1 else src.indexOf('{')
    val rootClose = if (rootOpen < 0) -1 else matchBracket(src, rootOpen)
    if (rootClose < 0) fresh
    else {
      val entry = jsonEntry(argv, extraFields)
      val msKey = findJsonKey(src, rootOpen + 1, rootClose, "mcpServers")
      if (msKey < 0) {
        val hadEntries = src.substring(rootOpen + 1, rootClose).trim.nonEmpty
        val block = s"""\n  "mcpServers": {\n    ${jsonString(serverName)}: $entry\n  }"""
        val comma = if (hadEntries) "," else ""
        src.substring(0, rootOpen + 1) + block + comma + src.substring(rootOpen + 1)
      } else {
        val objOpen = src.indexOf('{', src.indexOf(':', msKey))
        val objClose = matchBracket(src, objOpen)
        val snKey = findJsonKey(src, objOpen + 1, objClose, serverName)
        if (snKey >= 0) {
          val vs = skipWs(src, src.indexOf(':', snKey) + 1, objClose)
          val ve = jsonValueEnd(src, vs, objClose)
          src.substring(0, vs) + entry + src.substring(ve)
        } else {
          val hadEntries = src.substring(objOpen + 1, objClose).trim.nonEmpty
          val ins = s"""\n    ${jsonString(serverName)}: $entry${if (hadEntries) "," else ""}"""
          src.substring(0, objOpen + 1) + ins + src.substring(objOpen + 1)
        }
      }
    }
  }

  @tailrec private def skipWs(s: String, i: Int, limit: Int): Int =
    if (i < limit && s.charAt(i).isWhitespace) skipWs(s, i + 1, limit) else i

  private def withTrailingNewline(s: String): String =
    s.replaceAll("\\R+\\z", "") + "\n"

  private def leadingSpaces(line: String): Int = line.takeWhile(_ == ' ').length

  private def splitArgv(argv: Seq[String]): (String, Seq[String]) =
    argv match {
      case command +: args => command -> args
      case _               => "" -> Seq.empty
    }

  private def jsonValueEnd(s: String, vs: Int, limit: Int): Int =
    s.charAt(vs) match {
      case '{' | '[' => matchBracket(s, vs) + 1
      case '"'       =>
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

  private def colonFollows(s: String, from: Int, end: Int): Boolean = {
    val j = skipWs(s, from, end)
    j < end && s.charAt(j) == ':'
  }

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
    val (command, args) = splitArgv(argv)
    val argsToml = args.map(tomlString).mkString("[", ", ", "]")
    s"""|[mcp_servers.${tomlKey(serverName)}]
        |command = ${tomlString(command)}
        |args = $argsToml
        |startup_timeout_sec = 60
        |tool_timeout_sec = 60""".stripMargin
  }

  def mergeToml(
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

  private def continueItem(serverName: String, argv: Seq[String]): String = {
    val (command, argvArgs) = splitArgv(argv)
    val args =
      if (argvArgs.isEmpty) ""
      else argvArgs.map(a => s"\n      - ${yamlString(a)}").mkString("\n    args:", "", "")
    s"""|  - name: ${yamlString(serverName)}
        |    command: ${yamlString(command)}$args
        |    connectionTimeout: 60000""".stripMargin
  }

  private def renderContinueYaml(serverName: String, argv: Seq[String]): String =
    s"""|name: ScalaSemantic MCP
        |version: 1.0.0
        |schema: v1
        |mcpServers:
        |${continueItem(serverName, argv)}""".stripMargin

  def mergeYaml(
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
          val blockEnd =
            lines.indexWhere(l => !l.startsWith(" ") && l.trim.nonEmpty, msIdx + 1) match {
              case -1 => lines.length
              case e  => e
            }
          val nameLine = s"- name: ${yamlString(serverName)}"
          val itemIdx =
            (msIdx + 1).until(blockEnd).find(i => lines.lift(i).exists(_.trim == nameLine))
          itemIdx match {
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

  def writeRulesAndSteer(client: String, baseDir: File, log: Logger): Unit = {
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

    val normalized = client.trim.toLowerCase.replace('_', '-')
    val optLlmConfig = normalized match {
      case "claude" | "claude-code" | "anthropic" =>
        Some(
          (baseDir / "CLAUDE.md", "CLAUDE.md", "[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)")
        )
      case "gemini" | "google" | "google-gemini" | "gemini-cli" | "antigravity" |
          "antigravity-cli" | "agy" =>
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
