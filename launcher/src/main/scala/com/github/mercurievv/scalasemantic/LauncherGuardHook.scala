package com.github.mercurievv.scalasemantic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Claude Code `PreToolUse` guard hook.
  *
  * Steering files (`SCALA_SEMANTIC_RULES.md` and friends, see [[LauncherRules]]) only *ask* an
  * agent to prefer the MCP tools over `grep`/`cat` on Scala sources; models routinely fall back to
  * their text-search habit anyway. Claude Code hooks are enforced by the harness rather than by the
  * model, so this is the one integration point that can actually hold the rule. Other clients have
  * no equivalent mechanism and keep steering text only.
  */
private[scalasemantic] object LauncherGuardHook:
  val HookRelPath = ".claude/hooks/scala-semantic-guard.sh"
  private val SettingsRelPath = ".claude/settings.json"
  // Present in both the hook script and the settings entry, so "already installed" is a plain
  // substring check on either file — no JSON parsing needed for idempotency.
  private val Marker = "scala-semantic-guard"
  private val HookCommand = "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/scala-semantic-guard.sh"
  private val Matcher = "Read|Grep|Glob|Bash"

  def install(project: Path, client: String): Unit =
    if claudeSelected(client) then
      val hook = project.resolve(HookRelPath)
      Files.createDirectories(hook.getParent)
      val existing = if Files.exists(hook) then Some(Files.readString(hook)) else None
      if !existing.contains(script) then
        Files.writeString(hook, script)
        LauncherMessages.err(s"${if existing.isEmpty then "created" else "updated"} $hook")
      makeExecutable(hook)

      val settings = project.resolve(SettingsRelPath)
      val current = if Files.exists(settings) then Some(Files.readString(settings)) else None
      mergeSettings(current) match
        case Some(merged) =>
          Files.writeString(settings, merged)
          LauncherMessages.err(s"registered guard hook in $settings")
        case None => ()

  private def claudeSelected(client: String): Boolean =
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
  def mergeSettings(existing: Option[String]): Option[String] =
    val src = existing.getOrElse("")
    if src.contains(Marker) then None
    else if src.trim.isEmpty then Some(freshSettings)
    else
      val rootOpen = src.indexOf('{')
      val rootClose = if rootOpen < 0 then -1 else LauncherConfigMerge.matchBracket(src, rootOpen)
      if rootClose < 0 then Some(freshSettings)
      else
        val hooksKey = LauncherConfigMerge.findJsonKey(src, rootOpen + 1, rootClose, "hooks")
        if hooksKey < 0 then
          val hadEntries = src.substring(rootOpen + 1, rootClose).trim.nonEmpty
          val block = s"\n  \"hooks\": {\n    \"PreToolUse\": [\n$entry\n    ]\n  }"
          val comma = if hadEntries then "," else ""
          Some(src.substring(0, rootOpen + 1) + block + comma + src.substring(rootOpen + 1))
        else
          val hooksOpen = src.indexOf('{', src.indexOf(':', hooksKey))
          val hooksClose =
            if hooksOpen < 0 then -1 else LauncherConfigMerge.matchBracket(src, hooksOpen)
          if hooksClose < 0 then None
          else
            val preKey =
              LauncherConfigMerge.findJsonKey(src, hooksOpen + 1, hooksClose, "PreToolUse")
            if preKey < 0 then
              val hadEntries = src.substring(hooksOpen + 1, hooksClose).trim.nonEmpty
              val ins = s"\n    \"PreToolUse\": [\n$entry\n    ]${if hadEntries then "," else ""}"
              Some(src.substring(0, hooksOpen + 1) + ins + src.substring(hooksOpen + 1))
            else
              val arrOpen = src.indexOf('[', src.indexOf(':', preKey))
              val arrClose =
                if arrOpen < 0 then -1 else LauncherConfigMerge.matchBracket(src, arrOpen)
              if arrClose < 0 then None
              else
                val hadEntries = src.substring(arrOpen + 1, arrClose).trim.nonEmpty
                val ins = s"\n$entry${if hadEntries then "," else ""}"
                Some(src.substring(0, arrOpen + 1) + ins + src.substring(arrOpen + 1))

  private def entry: String =
    s"""|      {
        |        "matcher": "$Matcher",
        |        "hooks": [
        |          { "type": "command", "command": "${HookCommand.replace("\"", "\\\"")}" }
        |        ]
        |      }""".stripMargin

  private def freshSettings: String =
    s"""|{
        |  "hooks": {
        |    "PreToolUse": [
        |$entry
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
  val script: String =
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
       |Stale or missing index: run the project's compile task, then refresh_workspace — or, if you
       |cannot run the build yourself (e.g. this session cannot shell out), call refresh_workspace
       |with compile=true and it will detect and run the build itself.
       |If the semantic tools genuinely cannot answer this, re-run the command through Bash with
       |a trailing `# semantic-fallback: <reason>` marker (allowed, and logged).
       |MSG
       |exit 2
       |""".stripMargin
