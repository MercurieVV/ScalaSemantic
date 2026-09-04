# Local install channel: `./mill installLocal`

- **Date:** 2026-09-05
- **Status:** design approved, not yet implemented
- **Builds on:** `2026-09-05-install-ux-design.md` (single launcher script, user-scope install) and
  ADR-0004
- **Companion ADR to write during implementation:**
  `docs/adr/0005-local-jar-channel.md`

## Problem

Every install path today ends at a GitHub release. `scripts/scalasemantic-mcp.sh` resolves the
newest release tag, downloads `scalasemantic-mcp.jar` into
`~/.local/share/scalasemantic-mcp/`, and background-fetches newer releases on later starts. That is
right for users and wrong for the person developing the server: a change to `mcp/`, `analysis/` or
`core/` only reaches the machine's MCP clients after a tag, a CI publish, and a background fetch.

Working around it means per-client `SCALASEMANTIC_JAR` env entries — the launcher already honours
that variable (`scripts/scalasemantic-mcp.sh:82`), and the smoke tests use it
(`scripts/smoke-tests-local-run/test-install.sc:282`). But an env var lives in one client config for
one project. It is not global, and it has to be repeated and then remembered when it goes stale.

Wanted: edit code, run one command, and every MCP client in every project on this machine uses the
new build at its next start.

## Goals

1. One command — `./mill installLocal` — takes the working tree to globally installed.
2. It replaces the user-scope install completely: one binary on `PATH`, one active jar, no
   per-project configuration and no ambiguity about which build answers a tool call.
3. Release auto-update must not silently take the machine back off the local build.
4. An explicit, discoverable way back to the release channel.
5. No second copy of install logic: client configs, SemanticDB setup and the guard hook keep going
   through the `launcher/` module inside the fat jar.

## Non-goals

- **Coursier (and any other package-manager channel).** Coursier resolves transitive dependencies
  correctly and `./mill mcp.publishLocal` → `~/.ivy2/local` → `cs launch -r ivy2Local` would work.
  It is still the wrong tool here, for two reasons.

  *Merged channels have no total order.* A scheme that "uses the newest of the coursier artifact and
  the home-dir jar" must compare an Ivy/Maven semver against a file's mtime. Central's `0.4.2` and a
  local build of `0.4.2` thirty seconds old are not comparable; any rule invented for it is a rule
  to debug later, when the wrong build answers. This design keeps exactly one channel active at a
  time.

  *Resolution cost lands on every server start.* An MCP server process starts per client launch per
  project. `java -jar` needs no resolution and no network; `cs launch` adds a resolution step and an
  offline failure mode to a hot path, plus a second cache and second set of version semantics for a
  developer to hold in their head.

  Coursier remains reasonable as a *future alternative release channel* (`cs install` in place of
  the curl one-liner) for users who already live in coursier. If it is ever added, it replaces the
  release channel rather than merging with the local one.

- **Auto-rebuild.** The launcher never invokes `mill`. Freshness is the developer's explicit
  `./mill installLocal`, so server startup stays a `java -jar` with no build-tool dependency.
- **Windows.** `scripts/scalasemantic-mcp.ps1` is unchanged; a PowerShell equivalent is a follow-up.
- **Anything about the MCP tool surface, the index, or what the server answers.**

## Design

Three pieces: a Mill task, a launcher guard, and the escape hatch.

### 1. `./mill installLocal`

A `Task.Command` in `build.mill`, next to `smokeTest()`. Steps, in order:

1. `mcp.assembly()` — the same fat jar `smokeTest` and the release build use.
2. Copy `scripts/scalasemantic-mcp.sh` → `${BIN_DIR:-$HOME/.local/bin}/scalasemantic-mcp`,
   `chmod +x`. Same destination and same env override the launcher's own `self_install` uses, so a
   machine that already ran the curl one-liner is upgraded in place rather than gaining a second
   binary.
3. Delete any existing `*-local.jar` in `${SCALASEMANTIC_HOME:-$HOME/.local/share/scalasemantic-mcp}`,
   then copy the assembly there as `scalasemantic-mcp-<version>-local.jar`, where `<version>` is
   `publishVersion()` (highest `v*` tag, or `x.y.z`). Exactly one local jar exists at any time, so
   the cache does not grow and "which local build is active" has one answer.
4. Run `java -jar <that jar> install --scope user`, with `SCALASEMANTIC_JAR` set to it and
   `SCALASEMANTIC_LAUNCHER` set to the installed launcher path. This reuses the existing
   `launcher/` install path for client configs, SemanticDB setup and the guard hook — this task
   adds no install logic of its own.
5. Print the launcher path, the jar path, and that MCP clients must be restarted to pick it up.

The version suffix is a label for humans. Selection is by mtime (below), and step 3 writes the file
fresh, so the local jar is newest by construction.

### 2. Launcher guard in `jar_to_run`

`newest_cached()` is `ls -t` — newest **mtime**, not highest version. A freshly installed local jar
therefore already wins, but only until the background fetch downloads a release, whose newer mtime
would silently revert the machine to the release channel. Goal 3 needs one guard:

> In `jar_to_run`, before anything else except the `SCALASEMANTIC_JAR` override: if `newest_cached`
> matches `*-local.jar`, print nothing, return it, and do **not** call `resolve_tag`, download, or
> spawn `--bg-fetch`.

Precedence becomes: `SCALASEMANTIC_JAR` → local jar → `SCALASEMANTIC_VERSION`/release → newest
cached release. `SCALASEMANTIC_JAR` stays on top so the smoke tests, which set it explicitly, are
unaffected by whatever channel the developer's machine is on.

### 3. `scalasemantic-mcp --use-release`

Deletes `*-local.jar` from the data directory and exits, printing what it removed and that the next
start resolves a release. Parsed alongside `--bg-fetch` and `--prefetch`, after `resolve_self`.
Local mode replaces the user install fully, so the way out must be one documented command rather
than knowing which file to `rm`.

## Data flow

```
edit core/ analysis/ mcp/
  -> ./mill installLocal
       -> mcp.assembly
       -> ~/.local/bin/scalasemantic-mcp                     (launcher)
       -> ~/.local/share/scalasemantic-mcp/*-local.jar       (exactly one)
       -> java -jar <jar> install --scope user               (client configs, hook, semanticdb)
  -> restart MCP clients
  -> every project on the machine runs the new build

back to releases: scalasemantic-mcp --use-release
```

## Error handling

- `mcp.assembly()` failing fails the task; nothing is copied and the previously installed local jar
  stays active. Never leave the machine on a half-installed build.
- `BIN_DIR` not writable, or absent from `PATH`: the task fails with the path it tried. It does not
  edit shell profiles.
- Step 4 failing (`install --scope user`) fails the task, but the jar and launcher are already in
  place — the message says so, and says that re-running the task is safe.
- `--use-release` with no local jar present: says so, exit 0. Idempotent.

## Testing

TDD, in `scripts/smoke-tests-local-run/` (scala-cli, house style per `CLAUDE.md`), with
`SCALASEMANTIC_HOME` and `BIN_DIR` pointed at a temp directory so no test touches the real machine.

1. **Launcher selection** — seed a fake data dir with `scalasemantic-mcp-0.1.0-local.jar` and a
   *newer-mtime* `scalasemantic-mcp-9.9.9.jar`. Assert the launcher runs the local jar, and that it
   makes no network call and spawns no background fetch (point `REPO`/`RAW_URL` at an unreachable
   host so any attempt fails loudly, and assert the run still succeeds).
2. **`--use-release`** — after (1), run it; assert the local jar is gone, that a second run is a
   clean no-op, and that selection falls back to the newest cached release.
3. **`installLocal` end to end** — run the task against the temp `BIN_DIR`/`SCALASEMANTIC_HOME`,
   then assert an executable launcher exists, exactly one `*-local.jar` exists, and the installed
   launcher answers a real MCP `initialize` + `tools/list` over stdio. Re-run it and assert
   idempotence: still exactly one local jar, still answering.

## Documentation

- `docs/getting-started/integration.md` — a "developing on ScalaSemantic" section: the task, what it
  replaces, and `--use-release`.
- `docs/adr/0005-local-jar-channel.md` — records the `-local` filename sentinel as the channel
  marker, the no-auto-update rule that follows from it, and coursier as a considered-and-rejected
  alternative with the reasoning above.
