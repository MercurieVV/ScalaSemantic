# How Claude Code interacts with Scala — a usage study

A data-driven look at how Claude Code reads and writes Scala, used to decide which tools ScalaSemantic should add next.

Source: ~33 Claude Code session transcripts (`~/.claude/projects/*.jsonl`) for Scala projects under `IdeaProjects/my/` (ScalaSemanticMCP ~77%, remainder `film-video-enhancer` and `spents`).

> Caveat: small sample, skewed toward developing this tool. Treat counts as indicative; the behavioural patterns were consistent across projects.

## The numbers that matter

| Signal | Value | Reading |
|---|---|---|
| Edits vs reads on `.scala` | **245 : 71 (3.5 : 1)** | Claude edits Scala far more than it reads — and the server is read-only, so the dominant activity is unassisted. |
| Whole-file re-reads | `McpTools.scala` read 12×, edited 49× | Tight read → edit → re-read loop to relocate edit points. |
| Bash text-search on `.scala` | **68** (grep 46, find 14, cat 13, rg 3…) | vs **10** semantic-tool calls total. grep/read wins even where MCP exists. |
| Semantic tools used | `find_usages` 7, `find_symbol` 3 | 8 other tools (`class_hierarchy`, `method_signature`, `members`, `call_path`, etc.) — used **zero** times. |

## What the greps were for

- **~half**: build/test output filtering (`sbt … | grep -E "error|FAIL"`) — not the MCP's job.
- **"What is in this file"**: `grep -n "X\|Y" File.scala` to survey a file's API before editing.
- **Library surveys**: `git grep "upickle\|ujson"` — "does this codebase use X?"

## Recommended additions (data-ranked)

1. **`document_outline(uri)`** — highest ROI. Returns a file's declarations (kind + line range + signature). Replaces whole-file re-reads and API-survey greps. SemanticDB already has every symbol's definition range, so this is cheap and index-only. ✅ shipped
2. **Definition ranges** — `find_symbol`/`type_at_position` return a point; editing a method needs its full span. A range field lets Claude locate/replace without reading the file. ✅ shipped
3. **`rename_plan`** — edit-plan: exact `{uri, range}` edits derived from `find_usages`. Turns the read-only index into write assistance. ✅ shipped
4. **`imports` / `dependencies-of`** — serves the "does this codebase use X?" grep pattern. Lower priority.

The 8 unused tools and 68 greps vs 10 semantic calls show a **coverage gap** (no outline/edit tool means fall back to grep), not just a discoverability problem. `document_outline` removes the reason to grep.

## Collecting these logs in any project

### A. Live hook (forward-looking)

[`scripts/log-scala-interaction.py`](https://github.com/MercurieVV/ScalaSemantic/blob/master/scripts/log-scala-interaction.py) reads the Claude Code `PostToolUse` hook payload and appends one JSONL record per Scala-touching call.

Register in `.claude/settings.json`:

```json
{
  "hooks": {
    "PostToolUse": [{
      "matcher": "Read|Edit|Write|MultiEdit|Grep|Glob|Bash",
      "hooks": [{ "type": "command", "command": "python3 \"$CLAUDE_PROJECT_DIR/scripts/log-scala-interaction.py\"" }]
    }]
  }
}
```

Logs land in `~/.claude/scala-interactions.jsonl` (override with `SCALA_INTERACTION_LOG`). Quick analysis:

```bash
jq -r .op ~/.claude/scala-interactions.jsonl | sort | uniq -c   # counts by op
jq -r 'select(.op!="search" and .op!="bash") | .target' ~/.claude/scala-interactions.jsonl | sort | uniq -c | sort -rn | head
```

### B. Retroactive mine (existing transcripts)

Claude Code writes session transcripts to `~/.claude/projects/<url-encoded-path>/*.jsonl`. Extract Scala-touching tool calls:

```bash
DIR=~/.claude/projects/-Users-you-IdeaProjects-your-project
jq -c 'select(.message.content?) | .message.content[]
  | select(.type=="tool_use") | {tool: .name, input: .input}
  | select(
      (.input.file_path? // "" | endswith(".scala")) or
      (.input.command?   // "" | test("\\.scala")) or
      (([.input.pattern?, .input.glob?, .input.path?] | map(. // "") | join(" ")) | test("scala"; "i"))
    )' "$DIR"/*.jsonl
```

Then `jq … | sort | uniq -c` gives the edit:read ratio, grep vs semantic split, and most-churned files.

> This transcript format is Claude Code internal and can change between versions — treat the output as best-effort.
