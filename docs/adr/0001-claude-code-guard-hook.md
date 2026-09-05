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

`setup --rwhook-local` writes `.claude/hooks/scala-semantic-guard.sh` and registers it under
`hooks.PreToolUse` with matcher
`Read|Grep|Glob|Bash|Edit|Write|MultiEdit|mcp__scala-semantic__annotated_source`.
`--rwhook-user` installs the same hook into `$HOME/.claude` instead, registered by absolute path
(`$CLAUDE_PROJECT_DIR` points at the project being edited, which holds no copy of the script), so
it covers every project the user opens. `--rw-hook-remove` takes it out of both.

**Opt-in, not on by default.** Installing the hook silently changes how every later agent session
in that directory reads Scala — a `Read` the agent has always been able to make starts failing — so
it is not something `setup` should do as a side effect of registering an MCP server. A plain setup
run does still regenerate a hook that is already installed, so upgrading the launcher upgrades the
hook.

Denied (exit 2, reason on stderr so the agent reads it): `Read` of `.scala`/`.sc`; `Grep`/`Glob`
naming Scala; `Bash` running `grep|rg|ag|ack|cat|sed|awk|head|tail|less|more|nl` against a `.scala`
path. Everything else passes — builds, tests, git, non-Scala search.

Writes of a Scala source (`Edit`/`Write`/`MultiEdit`, or a shell redirect / `tee` / `sed -i` whose
target is a Scala path) are **allowed with a reminder** on stdout, which Claude Code feeds back as
context (see "Edits are reminded about, not denied" below).

The denial names the *server* and lists routing options, so the agent picks what fits:

```
BLOCKED by ScalaSemantic guard: text tools are not allowed on .scala sources here.
  symbols / references / types  -> find_symbol, find_usages, type_at_position
  hierarchy / members / givens  -> class_hierarchy, members, resolve_implicits
  …
```

### Edits are reminded about, not denied

Reading is a strict win for the semantic tools; editing is not. A three-line change through `Edit`
costs less than the whole-file roundtrip that `annotated_source`'s write mode requires. What the
write path buys is not safety but *sight*: the buffer it hands back carries the compiler's inferred
types, implicit arguments and conversions inline, so the edit is made against what the compiler
sees rather than against the text as written.

That is worth a nudge, not a wall, so the hook prints the recipe and exits 0:

```
annotated_source(uri, format="compilable", sentinel=true)   -> buffer + sha256
edit that buffer, leaving the SEM blocks in place
annotated_source(uri, write=<edited text>, baseHash=<sha256>)
```

Both arguments are load-bearing. `sentinel=true` renders each note as a machine-strippable
`/*SEM:...:SEM*/` block; `format=compilable` drops the read-only line-number gutter. Write mode
strips only SEM blocks, so a buffer read any other way would persist rendered notes or a gutter
into the source. Such a write is refused server-side rather than applied.

`setup --strict-edits` (`-StrictEdits` on PowerShell) regenerates the hook with that branch as a
denial, for sessions where coverage matters more than roundtrip cost. Off by default. The choice is
recorded in the generated script as `strict_edits=0|1`; nothing else differs between the variants.

Widening the matcher forced one change to registration: `mergeSettings` used to skip any
`settings.json` already naming the guard, which would have left every existing install on the old
matcher and made the edit branch dead code. It now rewrites that entry's `matcher` value in place
and leaves the rest of the file untouched.

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
- **Deny edits outright by default** — every one-line change would pay a whole-file roundtrip, and
  the guard would be switched off within a week. Kept available as `--strict-edits`.
- **Fire the reminder only when that file was already read through `annotated_source`** — the
  precise trigger, but it needs per-file state written by a `PostToolUse` hook and read by this one.
  Shelved until the stateless reminder proves too noisy.
- **Allow `Read`, block only search** — whole-file reads are the largest token sink in the measured
  logs, and `document_outline` + `symbol_source` cover "show me this code" precisely.
- **Staleness detection** — a full-tree mtime scan per call, and wrong in both directions on partial
  compiles. Existence is checked; staleness is left to `refresh_workspace` and the override.
- **Ship as a Claude Code plugin** — forks the install story and applies globally, including to
  non-Scala projects. Revisit if plugins become the primary distribution channel.

## Consequences

- The rule holds without depending on model compliance — the only such integration point here.
- Failing open is silent by construction, so a liveness bug in the hook is indistinguishable from a
  healthy install from the outside. (One shipped: the index probe pruned `out`/`target`, which is
  exactly where Mill and sbt emit SemanticDB, so the guard never blocked anything on a real
  project.) `scalasemantic-mcp doctor` — `LauncherDoctor`, also run at the end of `setup` — re-runs
  each of those conditions eagerly and names the ones that fail.
- Two implementations must stay in sync: `LauncherGuardHook` (the jar) and the PowerShell script.
  `GuardHookSuite` pins jar-side behaviour and diffs the PowerShell copy against it. (A third,
  `scripts/scalasemantic-mcp.scala`, was deleted in [ADR-0004](0004-single-launcher-script-and-user-scope-install.md).)
- User-scope installs get **no** hook: it needs project-local `.semanticdb` and config checks. See
  [ADR-0004](0004-single-launcher-script-and-user-scope-install.md).
- Windows: the body is `sh` with LF endings. A PowerShell-only environment fails to execute it,
  which the harness treats as "hook errored", i.e. fails open — consistent with the rest.
- If the fallback log fills with legitimate entries, the matching is too broad and should narrow.
