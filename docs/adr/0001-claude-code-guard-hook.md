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
  `hooks.PreToolUse` with matcher `Grep|Glob|Bash`.
- Denies (exit code 2, with the reason on stderr so the agent reads it):
  - `Grep`/`Glob` whose `glob`, `path` or `type` names Scala,
  - `Bash` invoking `grep|egrep|fgrep|rg|ripgrep|ag|ack` **on** a `.scala`/`.sc`/`.mill` path or on a
    `scala` source root — the tool must be the first word of its pipeline segment and the extension
    must end a path-like token, so `git add Foo.scala`, `mill test | tail` and package names such as
    `com.example.scalasemantic.Foo` are not mistaken for searching source.
- `Read`, and shell reads/edits/writes/runs (`cat`, `head`, `tail`, `sed`, `awk`, …), are **not**
  denied; see the two reversals below.
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
exists on the `Bash` path) and
deliberately auditable, so abuse shows up as log volume rather than as silence.

### Revision: `Read` unblocked

The original decision denied `Read` of Scala sources. That is reversed; the guard now covers search
only. Three reasons, in order of weight:

1. **It made editing impossible.** `Edit` and `Write` refuse to modify a file the session has not
   `Read` — a harness rule, and content fetched through an MCP tool does not satisfy it. With `Read`
   denied, the only remaining way to change a Scala file was a shell script doing blind string
   replacement: no uniqueness check, no diff preview, strictly less safe than the tool the denial
   disabled.
2. **The justification does not transfer from search to read.** Text *search* is denied because it
   fails invisibly — it misses renames, re-exports and inferred uses, and over-matches comments.
   Reading a named file has none of those failure modes; it returns exactly the file asked for. The
   token argument is also weaker than it looked: `annotated_source` returns the same source plus
   annotations, so it is richer, not cheaper.
3. **Every false denial spends the guard's authority.** The measured behaviour is that an agent
   blocked once reaches for `# semantic-fallback:` almost immediately — the denial message
   advertises it. Firing on legitimate work teaches the agent that the guard is noise, and the hatch
   then gets used for the search calls that actually matter.

Steering is kept where it belongs: a non-blocking `PreToolUse` advisory on `Read` of `.scala` points
at `document_outline` and `annotated_source`, and the model decides. This supersedes "Warn instead
of deny" for `Read` specifically — a warning is inadequate against a habit that produces wrong
answers (search) and sufficient against one that merely produces expensive right ones (read).

### Revision: narrowed to search only

The `Read` reversal above fixed the tool but not the principle behind it, so the same argument was
then applied to the `Bash` path: the guard now denies text **search** and nothing else. Dropped from
the denied set: `cat`, `head`, `tail`, `less`, `more`, `nl`, `sed`, `awk`.

The reason is that a Scala path next to one of those commands is usually not source inspection:

| Command | What it actually does |
|---|---|
| `cat > New.scala <<EOF` | writes a file |
| `cat x.sc \| scala-cli -` | runs it |
| `sed -i s/a/b/ Foo.scala` | edits it |
| `cat scripts/build.sc` | reads a file that may never have been compiled, so no MCP tool can answer |

Each of those was a false denial, and false denials are expensive in a way missed nudges are not:
the agent loses a working tool, burns turns, and learns the guard is noise — which is the failure
that destroys the guard's authority over the calls that do matter. Search has no equivalent
excuse: it fails invisibly, and `search_text` is an exact in-MCP replacement, so shelling out for it
is never right.

Two adjustments came with the narrowing. The search set gained `egrep`/`fgrep`/`ripgrep`, and the
target pattern gained a path component literally named `scala`, because `grep -r foo
core/src/main/scala` names no file and was slipping through — the most common shape of the exact
habit the guard exists to break.

Explicitly rejected while narrowing: **allow, but prepend an advisory** via `PreToolUse`
`additionalContext`. It is the "warn instead of deny" alternative under another name — steering text
that arrives after the model already chose. A second variant, appending the *real* semantic answer
via `PostToolUse` (a one-shot `serve` call costs ~1.4 s), was rejected too: the hook receives a
search *pattern*, not a symbol, so only bare identifiers resolve, and everything else degrades back
to another ignored reminder.

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

**Also deny `Read`, or not.** Originally denied, on the grounds that whole-file reads are the
largest single token sink in the measured logs and that `document_outline` + `symbol_source` cover
"show me this code" precisely. **Reversed** — see "Revision: `Read` unblocked".

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
