# ADR 0003 — Global install as the documented default, with fail-closed project-root discovery

- **Status:** accepted
- **Date:** 2026-09-04
- **Applies to:** `scripts/install.sh`, `docs/getting-started/quickstart.md`, `docs/getting-started/integration.md`,
  server root resolution (`Main.scalaServer` / new `ProjectRootDiscovery`)

## Context

Two related gaps, both about how the server learns "which project am I looking at":

1. **Quickstart's default flow copies a launcher script into the project** (curl into cwd, commit
   `scalasemantic-mcp.sh` alongside the project, run `./scalasemantic-mcp.sh setup`). A second flow
   already exists — `scripts/install.sh` installs one shared launcher to `~/.local/bin` and caches
   the fat jar once in `~/.cache/scalasemantic-mcp` for every project on the machine — but it is
   documented as "Option B", not the default, even though it needs less repetition (install once,
   reuse everywhere) and is what most CLI tools do.

2. **`install.sh`'s own printed example config is wrong and stale.** It suggests
   `"args": ["/abs/path/to/project-to-analyze"]` (missing the `serve` subcommand, and hard-coding an
   absolute path) while `docs/getting-started/integration.md`'s Option B section already documents
   the correct, portable form: `"args": ["serve", "."]`. An absolute project path in args defeats
   the whole point of ADR-0002 (relative/portable configs) the moment it's checked in or shared.

`"."` only works because the seven supported MCP clients (Claude Code, Codex, Gemini CLI, Cline,
Roo, Continue, Antigravity) each read a **project-local** config file (`.mcp.json`,
`.codex/config.toml`, etc.) and spawn the server with cwd = the project containing that file — this
is already relied on by ADR-0002 and documented in integration.md's "Worktrees and cwd changes"
section. Today the server does **zero validation** of that assumption: `positional.headOption`
defaults to `"."` and is handed straight to `SemanticIndex.fromProject` with no check that cwd
actually looks like a Scala project. Trusting cwd blindly is fine while install is per-project and
manually verified once; it gets riskier as the global/shared-binary flow becomes the default,
because a misconfigured client (or a client that changes its cwd contract later) fails silently —
the server indexes the wrong directory and returns confident-looking `count: 0` answers instead of
an error.

> **Amended by [ADR-0004](0004-single-launcher-script-and-user-scope-install.md).** Decision 3's
> fail-closed behaviour no longer exits the process: the server starts, lists its tools, and returns
> the error below on each tool call instead. Everything else here still holds. ADR-0004 also
> supersedes the two-script install layout described in Decision 1.

## Decision

**1. Global install becomes the documented default.** `docs/getting-started/quickstart.md`'s step 1
changes from curling the launcher into the project to running `install.sh`. The per-project script
flow (today's Option B... now reordered) stays documented in `integration.md` as an explicit
alternative for teams that want the launcher version-pinned and committed alongside the project.

**2. `install.sh`'s printed snippet is fixed** to match `integration.md`: `"args": ["serve", "."]`,
not an absolute path.

**3. The server validates cwd before trusting it, fail-closed.** New `ProjectRootDiscovery` runs
only when the root argument is the **default** `"."` (an explicit non-`"."` argument is always
trusted as-is — this preserves Option C / pinned-path configs and anything scripting the server
directly):

   1. If cwd itself carries a build marker (`build.mill`, `build.sc`, `build.sbt`,
      `project/build.properties`, `pom.xml`, `build.gradle`, `build.gradle.kts`, or a `project.scala`
      Scala-CLI marker) — use cwd. This is the common case and matches what all seven clients already
      do (client cwd IS project root).
   2. Else walk up ancestors from cwd (bounded to 8 levels, stopping at `$HOME` or the filesystem
      root) looking for the same markers, and use the first ancestor that has one — covers a client
      that spawns with cwd inside a monorepo subpackage.
   3. Else fail loudly: print an error to stderr and exit non-zero, rather than silently indexing
      cwd anyway. `SCALASEMANTIC_SKIP_ROOT_CHECK=1` opts back into the old unconditional-cwd
      behavior for a build tool with no recognized marker.

   Bare `.git` is deliberately **not** treated as a marker on its own — a monorepo super-repo or a
   dotfiles repo up the tree would false-positive; it only counts combined with one of the build
   markers above being present in the same candidate directory (`.git` is not actually consulted at
   all in the current rule set — see Alternatives).

## Alternatives considered

**Rely on `${workspaceFolder}`-style config variables instead of runtime discovery.** Several of
the seven clients (the VS Code-family ones: Cline, Roo, Continue) support variable substitution in
config values, which would let a *shared, non-project-local* config still resolve per-project. Not
chosen as the primary mechanism: it doesn't cover Claude Code, Codex, Gemini CLI, or Antigravity's
config formats, and per-project config files already solve portability without it (ADR-0002). Left
as a documentation note in `integration.md` for the clients that do support it, not implemented in
the server.

**Never fail — always fall back to cwd.** Simpler, but reintroduces exactly the silent-wrong-root
failure mode this ADR exists to close: a global binary launched with an unexpected cwd would index
garbage and answer `count: 0` for everything, indistinguishable from "this symbol doesn't exist."

**Match on `.git` alone.** Rejected — cheap but false-positive-prone (a monorepo super-repo's `.git`
sits above the actual Scala module; a home-directory dotfiles repo would match if walk-up reached
it). Build-tool markers are more specific to "this is a Scala project root."

## Consequences

- Quickstart installs one shared binary + shared jar cache; per-project script remains available,
  documented as the alternative.
- A misconfigured or unusual MCP client now gets a clear startup error instead of a silently wrong
  index — at the cost of a new (bounded, opt-outable) startup check on the default-root path only.
- Projects using a build tool with none of the recognized markers must either add one (e.g. an empty
  `build.sbt` is already required for SemanticDB anyway) or set `SCALASEMANTIC_SKIP_ROOT_CHECK=1`.
- `install.sh`'s example config and `integration.md` stay in sync (both say `["serve", "."]`); a
  future change to one should update the other.
