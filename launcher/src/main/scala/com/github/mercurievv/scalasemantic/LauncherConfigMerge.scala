package com.github.mercurievv.scalasemantic

import scala.annotation.tailrec

private[scalasemantic] object LauncherConfigMerge:
  def mergeJson(
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

  def mergeToml(existing: Option[String], serverName: String, argv: Seq[String]): String =
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

  def mergeYaml(existing: Option[String], serverName: String, argv: Seq[String]): String =
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

  private def renderCodexToml(serverName: String, argv: Seq[String]): String =
    val (command, args) = splitArgv(argv)
    val argsToml = args.map(tomlString).mkString("[", ", ", "]")
    s"""|[mcp_servers.${tomlKey(serverName)}]
        |command = ${tomlString(command)}
        |args = $argsToml
        |startup_timeout_sec = 60
        |tool_timeout_sec = 60""".stripMargin

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

  // Also used by LauncherGuardHook to splice into .claude/settings.json.
  def matchBracket(s: String, openIdx: Int): Int =
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

  def findJsonKey(s: String, start: Int, end: Int, key: String): Int =
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
