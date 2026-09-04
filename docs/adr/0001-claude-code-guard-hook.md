# ADR 0001 — Enforce "no text tools on Scala sources" with a Claude Code hook

- **Status:** accepted
- **Date:** 2026-07-25
- **Applies to:** `scalasemantic-mcp setup --scope project` (`LauncherGuardHook`, `scripts/scalasemantic-mcp.ps1`)

## Context

Every install writes steering files telling the agent to use the MCP tools instead of
`grep`/`cat`/`rg` on `.scala`. Steering text is a prior, not a constraint: agents reach for text
search anyway, because "open the file and grep it" is the most reinforced habit in a coding model
and each call looks locally reasonable. This project's own logs and
[the interaction study](../research/claude-interaction-study.md) show text tools still winning a
large share of Scala symbol questions.

Losing costs tokens (~90% more per symbol question) and correctness: text search misses renames,
re-exports, inferred uses and implicits, and over-matches comments and same-named identifiers.

Claude Code's `PreToolUse` hooks are run by the *harness* and can deny a call — not advice the model
can talk itself out of. No other supported client has an equivalent, and MCP gives a server no way
to install one.

## Decision

A project install writes `.claude/hooks/scala-semantic-guard.sh` and registers it under
`hooks.PreToolUse` with matcher `Read|Grep|Glob|Bash`. On by default; opt out with `--no-guard`.

Denied (exit 2, reason on stderr so the agent reads it): `Read` of `.scala`/`.sc`; `Grep`/`Glob`
naming Scala; `Bash` running `grep|rg|ag|ack|cat|sed|awk|head|tail|less|more|nl` against a `.scala`
path. Everything else passes — `Edit`/`Write`, builds, tests, git, non-Scala search.

The denial names the *server* and lists routing options, so the agent picks what fits:

```
BLOCKED by ScalaSemantic guard: text tools are not allowed on .scala sources here.
  symbols / references / types  -> find_symbol, find_usages, type_at_position
  hierarchy / members / givens  -> class_hierarchy, members, resolve_implicits
  …
```

### Fails open when the semantic answer is unavailable

A guard that blocks work it cannot justify gets deleted within a day. It allows the call when:
neither `jq` nor `python3` is on `PATH` (cannot parse its payload — must not guess); no
`scala-semantic` entry in the client config (nothing better to route to); no `*.semanticdb` anywhere
(never compiled, or not a Scala project).

### The override

```sh
rg foo src/Main.scala   # semantic-fallback: MCP server down, need the answer now
```

Always allowed, appended with a timestamp to `.claude/semantic-fallback.log`. Deliberately awkward
(it exists only on the `Bash` path, so a blocked `Read` must be rerouted through a shell) and
deliberately auditable — abuse shows up as log volume rather than silence.

## Rejected

- **Stronger wording in steering files** — the status quo that fails; prose cannot beat a habit that
  fires at tool-selection time.
- **Warn instead of deny** — steering text with extra steps; it arrives after the model already chose.
- **Allow `Read`, block only search** — whole-file reads are the largest token sink in the measured
  logs, and `document_outline` + `symbol_source` cover "show me this code" precisely.
- **Staleness detection** — a full-tree mtime scan per call, and wrong in both directions on partial
  compiles. Existence is checked; staleness is left to `refresh_workspace` and the override.
- **Ship as a Claude Code plugin** — forks the install story and applies globally, including to
  non-Scala projects. Revisit if plugins become the primary distribution channel.

## Consequences

- The rule holds without depending on model compliance — the only such integration point here.
- Two implementations must stay in sync: `LauncherGuardHook` (the jar) and the PowerShell script.
  `GuardHookSuite` pins jar-side behaviour and diffs the PowerShell copy against it. (A third,
  `scripts/scalasemantic-mcp.scala`, was deleted in [ADR-0004](0004-single-launcher-script-and-user-scope-install.md).)
- User-scope installs get **no** hook: it needs project-local `.semanticdb` and config checks. See
  [ADR-0004](0004-single-launcher-script-and-user-scope-install.md).
- Windows: the body is `sh` with LF endings. A PowerShell-only environment fails to execute it,
  which the harness treats as "hook errored", i.e. fails open — consistent with the rest.
- If the fallback log fills with legitimate entries, the matching is too broad and should narrow.
