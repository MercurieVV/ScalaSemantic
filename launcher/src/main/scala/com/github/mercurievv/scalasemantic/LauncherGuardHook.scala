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

  /** Tools the hook is registered for. Read-shaped tools are denied on Scala sources; the edit
    * tools are only reminded about (or denied too, under `--strict-edits`).
    */
  val Matcher = "Read|Grep|Glob|Bash|Edit|Write|MultiEdit|mcp__scala-semantic__annotated_source"

  def install(project: Path, client: String, strictEdits: Boolean = false): Unit =
    if claudeSelected(client) then
      val hook = project.resolve(HookRelPath)
      Files.createDirectories(hook.getParent)
      val body = script(strictEdits)
      val existing = if Files.exists(hook) then Some(Files.readString(hook)) else None
      if !existing.contains(body) then
        Files.writeString(hook, body)
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
    if src.contains(Marker) then upgradeMatcher(src)
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

  /** An install that predates a change to [[Matcher]] carries the old tool list, so the hook would
    * never be invoked for the tools added since. Rewrite that one entry's `"matcher"` value — the
    * one whose `"matcher"` most closely precedes the guard command — and leave the rest of the file
    * byte-for-byte alone. `None` when it is already current: nothing to write.
    */
  private def upgradeMatcher(src: String): Option[String] =
    val marker = src.indexOf(Marker)
    val key = src.lastIndexOf("\"matcher\"", marker)
    if key < 0 then None
    else
      val open = src.indexOf('"', src.indexOf(':', key) + 1)
      val close = if open < 0 then -1 else src.indexOf('"', open + 1)
      if close < 0 || src.substring(open + 1, close) == Matcher then None
      else Some(src.substring(0, open + 1) + Matcher + src.substring(close))

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
  def script(strictEdits: Boolean): String =
    s"""|#!/bin/sh
        |# Generated by ScalaSemantic MCP setup -- do not edit; re-run `scalasemantic-mcp setup`
        |# to regenerate, or `scalasemantic-mcp setup --no-guard` to stop installing it (then drop
        |# the PreToolUse entry from .claude/settings.json).
        |#
        |# Claude Code PreToolUse hook, two jobs:
        |#   READS  -- text-scraping tools on .scala sources are denied, so symbol questions go to
        |#             the ScalaSemantic MCP tools, which answer from compiler facts at a fraction
        |#             of the tokens and without missing renames/implicits/inferred uses.
        |#   EDITS  -- writing a .scala source is allowed but reminds the agent to edit the
        |#             annotated buffer instead, so it edits with the compiler's inferred types and
        |#             implicits in view. `setup --strict-edits` turns that reminder into a denial
        |#             that the `# semantic-fallback:` marker cannot bypass.
        |#   BUFFERS -- an annotated_source READ is rewritten (PreToolUse updatedInput) to
        |#             format=compilable + sentinel=true, so what the agent gets back is an
        |#             editable buffer rather than a read-only view.
        |#
        |# Exit codes: 0 = allow (stdout is fed back to the agent as context), 2 = deny (stderr is).
        |
        |set -u
        |strict_edits=${if strictEdits then 1 else 0}
        |""".stripMargin +
      """|
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
       |# --- upgrade a plain annotated_source read into an editable buffer ---------------------
       |# A read the agent asked for as `format=plain` (or with the default gutter view, or without
       |# `sentinel`) cannot be edited and written back: the gutter is not source, and notes that
       |# are not sentinel-delimited cannot be stripped. PreToolUse `updatedInput` rewrites the
       |# call in place, so the agent gets a buffer whatever it asked for, without a round trip.
       |# Writes (`write` present) are passed through untouched.
       |if [ "$tool" = mcp__scala-semantic__annotated_source ] && [ "$reader" = jq ]; then
       |  upgraded=$(printf '%s' "$payload" | jq -c '
       |    if (.tool_input | type) != "object" then empty
       |    elif (.tool_input | has("write")) then empty
       |    elif (.tool_input.format == "compilable" and .tool_input.sentinel == true) then empty
       |    else { hookSpecificOutput: {
       |             hookEventName: "PreToolUse",
       |             permissionDecision: "allow",
       |             permissionDecisionReason:
       |               "ScalaSemantic guard: upgraded this read to an editable annotated buffer (format=compilable, sentinel=true) so it can be edited and written back through annotated_source.",
       |             updatedInput: (.tool_input + { format: "compilable", sentinel: true }) } }
       |    end' 2>/dev/null)
       |  if [ -n "${upgraded:-}" ]; then
       |    printf '%s\n' "$upgraded"
       |  fi
       |  exit 0
       |fi
       |
       |# --- explicit human/agent override -----------------------------------------------------
       |# `rg foo *.scala   # semantic-fallback: <reason>` is allowed for READS, and logged so the
       |# override stays auditable instead of silent. Whether it applies is decided AFTER the call
       |# is classified, because it deliberately does not cover writes: a marker appended to
       |# `sed -i ... A.scala` would otherwise talk its way straight past --strict-edits, which is
       |# the one thing strict mode exists to prevent.
       |fallback=0
       |case "$command_line" in
       |  *semantic-fallback:*) fallback=1 ;;
       |esac
       |
       |# --- does this call target Scala sources, and is it a read or a write? -----------------
       |# mode: "" = not our business, "read" = text-scraping a Scala source, "write" = editing one.
       |mode=
       |case "$tool" in
       |  Read)
       |    case "$file_path" in
       |      *.scala | *.sc) mode=read ;;
       |    esac
       |    ;;
       |  Grep | Glob)
       |    # Only when the call itself names Scala: an unscoped repo-wide search may legitimately
       |    # be after comments, config or non-Scala files.
       |    case "$glob$path$ftype" in
       |      *scala*) mode=read ;;
       |    esac
       |    ;;
       |  Edit | Write | MultiEdit | NotebookEdit)
       |    case "$file_path" in
       |      *.scala | *.sc) mode=write ;;
       |    esac
       |    ;;
       |  Bash)
       |    # `.scala` must end a path, not merely appear: `mill.scalalib`, `scalafmt` and
       |    # `.scala-build` are not Scala sources, and blocking them blocks the build itself.
       |    if printf '%s' "$command_line" | grep -Eq '\.(scala|sc)([^[:alnum:]_-]|$)'; then
       |      if printf '%s' "$command_line" | grep -Eq \
       |        '(^|[|;&(`]|[[:space:]])(grep|rg|ag|ack|cat|sed|awk|head|tail|less|more|nl)([[:space:]]|$)'
       |      then
       |        mode=read
       |      fi
       |      # Handing the file to a runner is executing it, not reading it -- and the pipeline
       |      # that filters its OUTPUT (`scala-cli foo.sc | grep ...`) is not a text search either.
       |      if printf '%s' "$command_line" | grep -Eq \
       |        '(^|[|;&(`/]|[[:space:]])(scala-cli|scala|scalac|amm|mill|sbt|java)([[:space:]]|$)'
       |      then
       |        mode=
       |      fi
       |      # A redirect or in-place edit whose TARGET is the Scala file is a write, not a read --
       |      # and it outranks a reader that appears on the same line (`cat > A.scala`).
       |      if printf '%s' "$command_line" | grep -Eq \
       |        '(>>?|tee)[[:space:]]*"?[^[:space:]"]*\.(scala|sc)([[:space:]"]|$)|sed[[:space:]]+-i[^|;&]*\.(scala|sc)([[:space:]]|$)'
       |      then
       |        mode=write
       |      fi
       |    fi
       |    ;;
       |esac
       |[ -n "$mode" ] || exit 0
       |
       |# The override, now that we know what kind of call this is: it releases a read, and is
       |# logged either way so an attempt to use it on a write stays visible.
       |if [ "$fallback" = 1 ]; then
       |  printf '%s\t%s\t%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$mode" "$command_line" \
       |    >>"$root/.claude/semantic-fallback.log" 2>/dev/null
       |  if [ "$mode" = read ]; then exit 0; fi
       |fi
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
       |# The index lives INSIDE the build's output dir for every mainstream build tool -- Mill:
       |# out/<mod>/semanticDbData*.dest/classes/META-INF/semanticdb, sbt: target/scala-3.*/**/
       |# META-INF/semanticdb -- so `out`/`target`/`.scala-build` must NOT be pruned here, or the
       |# probe finds nothing and the guard silently fails open on every real project. Matching the
       |# distinctive META-INF/semanticdb path keeps the walk cheap without them.
       |index=$(find "$root" \
       |  \( -name .git -o -name node_modules -o -name .worktrees -o -name website \) -prune -o \
       |  -path '*/META-INF/semanticdb/*.semanticdb' -print 2>/dev/null | head -n 1)
       |[ -n "$index" ] || exit 0
       |
       |# --- editing a Scala source ------------------------------------------------------------
       |# Not a denial by default: a three-line change through Edit is cheaper than a whole-file
       |# roundtrip. The reminder exists because the annotated buffer is what makes the edit
       |# compiler-aware, and nothing else in the session mentions it at the moment of the edit.
       |if [ "$mode" = write ]; then
       |  if [ "$strict_edits" = 1 ]; then
       |    cat >&2 <<'MSG'
       |BLOCKED by ScalaSemantic guard (--strict-edits): edit .scala sources through the MCP write
       |path, so the edit is made against the compiler's view of the file:
       |  1. annotated_source(uri, format="compilable", sentinel=true)
       |     -> the source with inferred types, implicit args and conversions inline as
       |        /*SEM:...:SEM*/ blocks (no line-number gutter), plus its sha256
       |  2. edit that buffer, leaving every SEM block exactly where it is -- they are stripped
       |     for you, and taking them out by hand edits lines your change does not concern
       |  3. annotated_source(uri, write=<edited text>, baseHash=<that sha256>)
       |     -> every SEM block is stripped before the file is saved
       |Both arguments matter: sentinel=true makes the notes machine-strippable, format=compilable
       |drops the read-only gutter. Any other read is a view, not a buffer -- writing it back would
       |persist annotations into the source, and is refused.
       |A `# semantic-fallback:` marker does NOT exempt a write: under --strict-edits this is the
       |only way to change a .scala source.
       |Re-run `scalasemantic-mcp setup` without --strict-edits to make this a reminder instead.
       |MSG
       |    exit 2
       |  fi
       |  cat <<'MSG'
       |ScalaSemantic: editing a Scala source. For an annotation-aware edit, work on the annotated
       |buffer instead of the raw text:
       |  1. annotated_source(uri, format="compilable", sentinel=true)
       |     -> inferred types, implicit args and conversions inline as /*SEM:...:SEM*/ blocks
       |        (no line-number gutter), plus its sha256
       |  2. edit that buffer, leaving every SEM block exactly where it is -- they are stripped
       |     for you, and taking them out by hand edits lines your change does not concern
       |  3. annotated_source(uri, write=<edited text>, baseHash=<that sha256>)
       |     -> SEM blocks are stripped before the file is saved
       |Both arguments matter: sentinel=true makes the notes machine-strippable, format=compilable
       |drops the read-only gutter. Any other read is a view, not a buffer -- writing it back would
       |persist annotations into the source, and is refused.
       |A small mechanical edit can stay with this tool -- this is a reminder, not a refusal.
       |MSG
       |  exit 0
       |fi
       |
       |# --- deny ------------------------------------------------------------------------------
       |cat >&2 <<'MSG'
       |BLOCKED by ScalaSemantic guard: text tools are not allowed on .scala sources here.
       |Text search misses renames, re-exports, implicits and inferred uses, and over-matches
       |comments and same-named identifiers.
       |To READ a Scala file, this is the tool:
       |  annotated_source(uri)
       |     -> the whole source, plus the inferred types, implicit arguments and conversions the
       |        compiler resolved, inline. `cat` shows none of that.
       |To EDIT one, read it as a buffer and write that buffer back:
       |  annotated_source(uri, format="compilable", sentinel=true)   -> text + its sha256
       |  annotated_source(uri, write=<edited text>, baseHash=<that sha256>)
       |     -> the /*SEM:...:SEM*/ blocks are stripped before the file is saved
       |Leave those blocks where they are in the text you send: the server removes them, and
       |removing them yourself edits lines your change does not concern.
       |For anything else, pick the tool that fits the question:
       |  symbols / references / types  -> find_symbol, find_usages, type_at_position
       |  hierarchy / members / givens  -> class_hierarchy, members, resolve_implicits
       |  signatures / overloads        -> method_signature, find_overloads
       |  file or project shape         -> document_outline, structure, symbol_source
       |  literals, comments, TODOs     -> search_text
       |Stale or missing index: run the project's compile task, then refresh_workspace — or, if you
       |cannot run the build yourself (e.g. this session cannot shell out), call refresh_workspace
       |with compile=true and it will detect and run the build itself.
       |If the semantic tools genuinely cannot answer this, re-run the READ through Bash with a
       |trailing `# semantic-fallback: <reason>` marker (allowed, and logged). It releases reads
       |only -- a write still has to go through annotated_source.
       |MSG
       |exit 2
       |""".stripMargin
