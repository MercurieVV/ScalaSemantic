# How Claude Code interacts with Scala — a usage study

A data-driven look at how Claude Code actually reads and writes Scala, used to decide which tools
ScalaSemantic should add next. Source: Claude Code session transcripts
(`~/.claude/projects/*.jsonl`) for the Scala projects under `IdeaProjects/my/` — ~33 sessions, of
which ScalaSemanticMCP is ~77%, the rest `film-video-enhancer` and `spents`.

> Caveat: the sample is small and skewed toward developing *this* tool. Treat the absolute counts as
> indicative, not precise. The behavioural patterns, however, were consistent across projects.

## The numbers that matter

| Signal | Value | Reading |
| --- | --- | --- |
| Writes vs reads on `.scala` (`Edit`/`Write` vs `Read`) | **245 : 71 (≈3.5 : 1)** | Claude *edits* Scala far more than it reads it — and the MCP server is **read-only**, so the dominant activity is unassisted. |
| Whole-file re-reads | `McpTools.scala` read 12×, edited 49×; `main.scala` 9 reads / 26 edits | A tight read → edit → re-read loop; files are read repeatedly mostly to relocate edit points. |
| Bash text-search on `.scala` | **68** (`grep` 46, `find` 14, `cat` 13, `rg` 3, …) | versus **10** semantic-tool calls in total. Even where the MCP exists, grep/read still wins. |
| Semantic tools actually used | `find_usages` 7, `find_symbol` 3 | The other eight tools (`class_hierarchy`, `method_signature`, `members`, `call_path`, `resolve_implicits`, `trace_implicit_chain`, `find_overloads`, `type_at_position`) were used **zero** times. |

## What the greps were actually for

Classifying the 68 Bash text-searches on `.scala`:

- **~half are build/test output filtering** (`sbt … | grep -E "error|FAIL"`) and `curl` downloads —
  legitimately *not* the MCP's job.
- **"What is in this file"** — `grep -n "X\|Y\|Z" SomeFile.scala` to survey a file's API before
  editing it.
- **Multi-identifier / library surveys** — `git grep "write\|upickle\|ujson\|toJson" -- '*.scala'`,
  `grep -rniE "http|socket|akka|http4s|netty|cask"` ("does this project use X").

## Recommended additions (data-ranked)

### 1. `document_outline(uri)` — highest ROI
Return a file's declarations (class/object/trait/def/val) with **kind + line range + one-line
signature**. Replaces *both* the whole-file re-reads (`McpTools.scala` read 12×) *and* the
`grep -n "X\|Y" File.scala` surveys. With a 3.5:1 edit ratio, the recurring question is "where in
this file is the thing I need to change" — an outline with ranges answers it in a few tokens instead
of a full file read. SemanticDB already carries every symbol's definition range, so this is cheap and
index-only.

### 2. Definition **range** (not just a point)
`find_symbol` / `type_at_position` return a `uri:line:col` *point*; editing a member needs its full
**span** (start–end line). A `definition` tool (or a `range` field on existing results) lets Claude
locate/replace a whole method without reading the file. Pairs with #1 to close the edit-targeting
gap.

### 3. A write-path / refactor helper — the biggest structural gap
Writes dominate and get zero assistance. Keeping the server read-only, the realistic first step is an
**edit-plan**: `rename_plan(symbol, newName)` → the exact list of `{uri, range}` edits derived from
`find_usages`, so Claude applies a safe rename precisely instead of hand-grepping. It turns the
read-only index into write *assistance*.

### 4. Lower priority: `imports` / `dependencies-of(file | module)`
Serves the `grep "akka\|http4s\|socket"` "does this codebase use X / what does it pull in" pattern.
The `memberType` dependency graph already computes the external edges that the structure feature
currently drops.

## What the data says *not* to do

Do not add more deep-query tools. The eight unused tools show the common path is **find-where →
edit**, not deep semantic analysis. And 68 greps vs 10 semantic calls indicate a **coverage gap** (no
outline/edit tool ⇒ fall back to grep), not merely a discoverability one. Sharpening the tool
instructions helps habit; `document_outline` removes the *reason* to grep.

## Suggested order

1. `document_outline` (cheap, kills the dominant read/grep pattern),
2. definition **ranges** on symbols,
3. `rename_plan` edit-assist,
4. `imports` / module-dependency surface.
