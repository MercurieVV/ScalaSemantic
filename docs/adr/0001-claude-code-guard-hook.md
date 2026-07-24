# ADR 0001 — Enforce the "no text tools on Scala sources" rule with a Claude Code hook

- **Status:** accepted
- **Date:** 2026-07-25
- **Applies to:** `scalasemantic-mcp setup` (jar launcher, `scripts/scalasemantic-mcp.scala`, `scripts/scalasemantic-mcp.ps1`)

## Context

Every ScalaSemantic install writes steering files: `SCALA_SEMANTIC_RULES.md` plus a per-client
pointer (`CLAUDE.md`, `AGENTS.md`, `.cursorrules`, `.clinerules`, …). They say, in effect: *for
symbol, type, hierarchy, implicit, reference or call-path questions on `.scala` files, use the
ScalaSemantic MCP tools instead of `grep`/`cat`/`rg`.*

Steering text is a prior, not a constraint. Agents keep reaching for text search anyway, because
"open the file and grep it" is the single most reinforced habit in a coding model, and because
each individual call looks locally reasonable ("I only wanted to see the file"). The failure is
observable in this project's own logs and in the interaction study
([`docs/research/claude-interaction-study.md`](../research/claude-interaction-study.md)): the tools
are installed and documented, and text tools still win a large share of Scala symbol questions.

The cost of losing is not only tokens (~90% more for symbol questions, per
[`explanation/scala-semantic-vs-grep`](../explanation/scala-semantic-vs-grep.md)) but correctness:
text search misses renames, re-exports, inferred uses and implicits, and over-matches comments,
strings and unrelated same-named identifiers.

Claude Code exposes `PreToolUse` hooks — commands the *harness* runs before a tool call, which can
deny it. A hook is not advice the model can talk itself out of; it either allows or blocks. No
other supported client (Codex, Gemini CLI, Cline, Roo, Continue, Antigravity) has an equivalent
mechanism today, and the MCP protocol itself has no way for a server to install one.

## Decision

`setup` installs a `PreToolUse` guard hook for Claude Code, **on by default**, opt out with
`--no-guard` (`-NoGuard` in PowerShell).

- Script: `.claude/hooks/scala-semantic-guard.sh`, registered in `.claude/settings.json` under
  `hooks.PreToolUse` with matcher `Read|Grep|Glob|Bash`.
- Denies (exit code 2, with the reason on stderr so the agent reads it):
  - `Read` of a `.scala` / `.sc` file,
  - `Grep`/`Glob` whose `glob`, `path` or `type` names Scala,
  - `Bash` running `grep|rg|ag|ack|cat|sed|awk|head|tail|less|more|nl` against a `.scala` path.
- The denial message names the MCP **server**, not one tool, and lists the routing options
  (`find_symbol`/`find_usages`/`type_at_position`, `class_hierarchy`/`members`/`resolve_implicits`,
  `method_signature`/`find_overloads`, `document_outline`/`structure`/`symbol_source`,
  `search_text`) so the agent picks what actually fits its question.
- Everything else passes untouched: `Edit`/`Write`, builds, tests, git, and any search that does
  not name Scala.

Only Claude Code gets the hook; the other clients keep steering text only. The generated
`SCALA_SEMANTIC_RULES.md` documents that the rule is enforced, so the agent's own context explains
a denial it may hit.

### Fail-open conditions

A guard that blocks work it cannot justify gets deleted within a day, so the hook allows the call
whenever the semantic answer is not actually available:

| Condition | Why |
|---|---|
| Neither `jq` nor `python3` on `PATH` | The hook cannot parse its own payload; it must not guess. |
| No `scala-semantic` entry in `.mcp.json` / `.claude/settings*.json` | The MCP server is not wired into this project — there is nothing better to route to. |
| No `*.semanticdb` anywhere in the project | Never compiled, or not a Scala project: the tools would answer from an empty index. |
| Command carries a `# semantic-fallback: <reason>` marker | Explicit human/agent override (see below). |

### The override

`rg foo src/Main.scala   # semantic-fallback: <reason>` is always allowed and is appended to
`.claude/semantic-fallback.log` with a timestamp. This is the pressure valve for "the MCP server is
down" or "the index is stale and I need an answer now" — deliberately slightly awkward (it only
exists on the `Bash` path, so a blocked `Read` has to be rerouted through a shell command) and
deliberately auditable, so abuse shows up as log volume rather than as silence.

## Alternatives considered

**Stronger wording in the steering files.** Free. Already tried; it is the status quo that fails.
Prose cannot beat a habit that fires at tool-selection time.

**Ship as a Claude Code plugin (marketplace) instead of a setup step.** A plugin can bundle hooks
and an MCP server and install once for all projects. Rejected for now: it forks the install story
(`setup` for everyone else, plugin for Claude), it applies globally including to non-Scala
projects, and the hook needs the project-local `.semanticdb`/`.mcp.json` checks anyway. Revisit if
the plugin surface becomes the primary distribution channel.

**Warn instead of deny (exit 0 + stderr).** Rejected: a non-blocking warning is just steering text
with extra steps — it arrives after the model already chose, and models routinely proceed anyway.

**Also deny `Read`, or not.** Considered allowing `Read` of `.scala` (an agent about to *edit* a
file legitimately reads it) and blocking only search. Rejected: whole-file reads are the largest
single token sink in the measured logs, and `document_outline` + `symbol_source` cover the "show me
this code" case precisely. `Edit`/`Write` stay unblocked, and the fallback marker covers the rest.

**Staleness detection** (deny only when the index is fresher than the sources). Rejected as the
per-call cost is a full-tree mtime scan and the heuristic is wrong in both directions on partial
compiles. Existence is checked; staleness is left to `refresh_workspace` and the override marker.

## Consequences

- The rule now holds in Claude Code without depending on model compliance — this is the only
  integration point in the project where that is true.
- `setup` writes into `.claude/settings.json`, a file the user may also hand-edit. The merge splices
  the entry in and preserves everything else; a re-run is a no-op once the marker is present.
- Three implementations of `setup` must stay in sync (jar `LauncherGuardHook`, the scala-cli script,
  the PowerShell script). The script body is duplicated verbatim; `GuardHookSuite` pins the jar-side
  behaviour, and the scala-cli script's output is diffed against it.
- Windows: the hook body is `sh`, written with LF endings. It runs under Git Bash/WSL; a
  PowerShell-only environment will fail to execute it, which the harness treats as "hook errored"
  rather than "denied" — i.e. it fails open, consistent with the rest of the design.
- Agents will occasionally hit a denial where the semantic tools genuinely cannot answer. The
  fallback log is the signal to widen the fail-open rules; if it fills up with legitimate entries,
  the guard's matching is too broad and should be narrowed.
