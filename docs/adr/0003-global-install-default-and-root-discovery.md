# ADR 0003 — What counts as a Scala project root

- **Status:** partly superseded by [ADR-0004](0004-single-launcher-script-and-user-scope-install.md)
- **Date:** 2026-09-04
- **Applies to:** `ProjectRootDiscovery`
- **Superseded parts:** the install-flow decisions here (global install as the documented default,
  and a fix to the now-deleted `scripts/install.sh`) are replaced wholesale by ADR-0004. The
  *fail-closed* behaviour is amended by it too: discovery failure no longer exits the process.
  What remains live is the discovery rule below.

## Context

Generated configs pass `.` as the server root, relying on every supported client spawning the server
with cwd = the project holding the config. The server did **zero** validation of that: `.` went
straight to `SemanticIndex.fromProject`. Trusting cwd blindly is tolerable when the launcher lives
in the project and was verified once by hand; it is not once one shared binary is registered for
every project on the machine, because a client with an unexpected cwd then indexes the wrong
directory and returns confident-looking `count: 0` answers instead of an error.

## Decision

Validate only when the root argument is the default `"."` — an explicit root is always trusted as
given, which preserves pinned-path configs and anything scripting the server directly.

1. **cwd carries a build marker** → use cwd. The common case; all supported clients already do this.
   Markers: `build.mill`, `build.sc`, `build.sbt`, `pom.xml`, `build.gradle`, `build.gradle.kts`,
   `project.scala`, or nested `project/build.properties`.
2. **Otherwise walk up** — at most 8 levels, stopping at `$HOME` or the filesystem root — and use the
   first ancestor with a marker. Covers a client spawned inside a monorepo subpackage.
3. **Otherwise report failure** rather than indexing cwd anyway. `SCALASEMANTIC_SKIP_ROOT_CHECK=1`
   opts back into unconditional cwd for a build tool with no recognised marker.

```
/repo/build.sbt                  ← marker
/repo/modules/core/  ← cwd       → walks up, resolves /repo
/tmp/some-python-app/ ← cwd      → no marker within range: failure
```

**How step 3 reports** is ADR-0004's change: it was a `sys.exit(1)` at startup; it is now an error
returned from each tool call, so the server stays connectable in non-Scala directories.

## Rejected

- **Match on `.git` alone** — cheap but false-positive-prone: a monorepo super-repo's `.git` sits
  above the actual Scala module, and a dotfiles repo in `$HOME` would match. Build markers are
  specific to "this is a Scala project root".
- **Never fail, always fall back to cwd** — reintroduces exactly the silent-wrong-root failure this
  exists to close.
- **`${workspaceFolder}`-style config variables instead of runtime discovery** — only the VS Code
  family supports them; Claude Code, Codex, Gemini CLI and Antigravity do not.

## Consequences

- A misconfigured client gets a clear error instead of a silently wrong index.
- A project whose build tool uses none of the markers must add one (an empty `build.sbt` is needed
  for SemanticDB anyway) or set `SCALASEMANTIC_SKIP_ROOT_CHECK=1`.
