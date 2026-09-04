# Install UX: one script, two one-liners, user scope by default

- **Date:** 2026-09-05
- **Status:** design approved, not yet implemented
- **Supersedes:** the two-script install layout (`scripts/install.sh` + `scripts/scalasemantic-mcp.sh`)
- **Amends:** ADR-0003 §3 (fail-closed root discovery moves from startup to tool-call time)
- **Companion ADR to write during implementation:** `docs/adr/0004-single-launcher-script-and-user-scope-install.md`

## Problem

Installing ScalaSemantic today takes two steps that never end: run `scripts/install.sh` once per
machine, then `scalasemantic-mcp setup` once per Scala project, forever. The install surface is also
split across three implementations of the same logic:

- `scripts/install.sh` — shell bootstrap, prints a config snippet.
- `scripts/scalasemantic-mcp.sh` — shell launcher: jar cache + `exec java -jar`.
- `scripts/scalasemantic-mcp.scala` — a 45 KB scala-cli script that re-implements client-config
  writing, SemanticDB configuration and the guard hook.

That third file duplicates the `launcher/` module, which already owns exactly this logic in Scala
(`LauncherSetup`, `LauncherClientConfigs`, `LauncherConfigMerge`, `LauncherGuardHook`,
`SbtSemanticdbConfig`, `LauncherMessages`, `LauncherRules`) and is already inside the fat jar via
`mcp.moduleDeps = Seq(analysis, build.launcher)`. Two copies drift; one already has (the scala-cli
copy still emits `scala-cli run --dependency ... -- .` argv, the module emits `serve .`).

## Goals

1. One shell script in the repo, as small as it can be. Everything else is Scala in the fat jar.
2. Two one-liners: global install, and project install. Nothing else to run afterwards.
3. Registering globally must not put a broken MCP server in every non-Scala repo on the machine.
4. An end-to-end install test, written first (TDD), idempotent, that verifies the server actually
   answers through real MCP clients.

## Non-goals

- Package-manager distribution (Homebrew, SDKMAN, npm). Out of scope; the curl one-liner is the
  supported path.
- Windows. `scripts/scalasemantic-mcp.ps1` keeps its current behaviour and is not part of this
  work; it may be brought in line in a follow-up.
- Changing the MCP tool surface, the index, or anything the server answers.

## Decision 1 — one script, self-installing

`scripts/install.sh` and `scripts/scalasemantic-mcp.scala` are **deleted**.
`scripts/scalasemantic-mcp.sh` becomes both installer and launcher, keeping its current
responsibilities (resolve release tag, download and cache the fat jar, background refresh,
`exec java -jar "$JAR" "$@"`) and gaining exactly one new one: **self-install when it cannot
resolve its own path.**

Today the script computes `SELF` from `$0`. Piped through `sh`, `$0` is `sh` — unusable. New rule:

```
if $0 does not resolve to a readable file containing this script:
    re-download self to the target launcher path (global bin, or ./scalasemantic-mcp.sh with --project)
    chmod +x
    exec that copy with:  install <original args>
```

This is the entire reason a second bootstrap script existed. With it, the same URL serves both
one-liners, and no user ever needs `scala-cli` to install.

Everything the deleted scala-cli script did is already implemented in `launcher/` and is reached
through `Launcher.run`, which already accepts `install` as an alias of `setup`.

The script must stay small — target ≤ 100 lines. Any new install *decision* belongs in Scala, not
here. Reviewer rule of thumb: if it needs to know about a client, a config format, or a build tool,
it is in the wrong file.

## Decision 2 — two one-liners

```sh
# global (default): launcher + jar under $HOME, MCP registered for every project on this machine
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh

# project: run from the repo root — launcher committed into the repo, MCP registered for this repo
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh -s -- --project
```

| | launcher | jar cache | configs written | `command` in config |
|---|---|---|---|---|
| user (default) | `$HOME/.local/bin/scalasemantic-mcp` | `$HOME/.local/share/scalasemantic-mcp/` | user-level (see Decision 3) | absolute `$HOME/.local/bin/scalasemantic-mcp` |
| `--project` | `./scalasemantic-mcp.sh` in the repo, committable | same shared cache | project-level, as today | relative `./scalasemantic-mcp.sh` (ADR-0002) |

Notes:

- The jar cache is shared by both modes. A project install never duplicates the ~88 MB jar.
- `BIN_DIR` keeps overriding the user-mode bin directory.
- User-mode configs use an **absolute** command deliberately. GUI-launched MCP clients frequently
  spawn without `$HOME/.local/bin` on `PATH`; a bare `scalasemantic-mcp` fails there with an opaque
  ENOENT. `LauncherClientConfigs.relativizeCommand` already leaves absolute paths outside the
  project untouched, so user-scope writes need no change to it.
- Project mode continues to relativize, per ADR-0002, so the committed config survives clones.
- The jar cache move from `${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp` to
  `$HOME/.local/share/scalasemantic-mcp` is deliberate: a downloaded release jar is installed data,
  not a cache, and must not be evicted by cache cleaners. Existing cached jars are not migrated;
  the first run after upgrade re-downloads once.

## Decision 3 — `--scope user|project` in `LauncherSetup`

`Launcher.run` already routes `install` to `LauncherSetup.setup`. The work is in the module:

- `LauncherSetup.Options` gains `scope: Scope` (`enum Scope { User, Project }`), defaulting to
  `Project` for a bare `setup` (backwards compatible with every documented invocation today) and
  set to `User` by the global one-liner, which passes `--scope user` explicitly.
- `LauncherSetup.parse` accepts `--scope user|project`; an unrecognised value is a hard error.
- `LauncherClientConfigs.Target` gains `userPath: Option[String]` alongside today's `relPath`.
  `None` means *this client has no user-level MCP config*. With `--scope user`, such a client is
  **skipped with a printed note**, never guessed at.

Per-client user paths, resolved against `$HOME`:

| client | project path (unchanged) | user path |
|---|---|---|
| claude | `.mcp.json` | `~/.claude.json` |
| codex | `.codex/config.toml` | `~/.codex/config.toml` |
| gemini | `.gemini/settings.json` | `~/.gemini/settings.json` |
| continue | `.continue/config.yaml` | `~/.continue/config.yaml` |
| antigravity | `.agents/mcp_config.json` | verify during implementation; `None` if unconfirmed |
| cline | `.cline/mcp.json` | verify during implementation; `None` if unconfirmed |
| roo | `.roo/mcp.json` | verify during implementation; `None` if unconfirmed |

Claude's user path is the one that does not follow the `$HOME/<same relative path>` pattern —
`~/.claude.json`, not `~/.mcp.json`. The three marked *verify* are settled by reading each client's
documentation while implementing; whichever cannot be confirmed ships as `None` and is reported as
skipped. Shipping a guessed path is worse than shipping none: a wrong path writes a file nobody
reads and the user believes they are installed.

`LauncherConfigMerge` is unchanged — the same JSON/TOML/YAML merge runs against a different file.
Its existing guarantees (preserve unrelated servers, no duplicate entries) are what make the
install idempotent, and the test asserts that directly.

`ensureSemanticdbConfig` and the guard hook are **project-scoped concerns and stay project-scoped**:
with `--scope user` they are skipped, because there is no project to configure. The user-mode
one-liner therefore only installs the launcher, the jar, and client registrations.

## Decision 4 — root discovery fails at call time, not startup

ADR-0003 made a missing build marker fatal at startup (`Main.scala`: `System.err.println(error);
sys.exit(1)`). That is correct for a per-project install and wrong for a global one: with the
server registered user-scope, every Python, Node, Go or docs repo the user opens spawns it, finds
no marker, and exits non-zero — a permanent "failed to connect" badge in every non-Scala project on
the machine.

New behaviour:

- `serveMcp` no longer exits when `ProjectRootDiscovery.resolveDefaultRoot` returns `Left`. The
  server completes the MCP handshake and lists its tools normally.
- The unresolved-root state is carried into `Mcp.serve`. Every **tool call** in that state returns
  the existing error message as a tool error — text preserved verbatim, so the guidance
  ("pass the project root explicitly", "`SCALASEMANTIC_SKIP_ROOT_CHECK=1`") still reaches the agent.
- `ProjectRootDiscovery` itself is unchanged: same markers, same 8-level bounded walk, same
  `$HOME` stop, same skip env var.

ADR-0003's actual goal — never answer a confident `count: 0` from a silently wrong index — is fully
preserved, because the server still refuses to index an unvalidated cwd. Only the *channel* for
reporting it changes, from process exit code to tool-call error. The exit code was the wrong channel
the moment the binary became global.

## Decision 5 — end-to-end install test, TDD, local only

New `scripts/test-install.sc` (scala-cli, house style per CLAUDE.md). **Written first and failing**
before any of Decisions 1–4 are implemented.

Not in CI: it drives real `claude`, `codex` and `agy` binaries, which need authentication. It is a
local pre-release gate, documented as such.

Isolation: every run uses a fresh temp directory as `HOME`, and a temp fixture Scala project
(`build.sbt` with `semanticdbEnabled := true`, one source file with a distinctively-named symbol),
compiled once so a real SemanticDB exists. The installer under test is the local checkout's
`scripts/scalasemantic-mcp.sh`, piped to `sh` so the self-install path of Decision 1 is exercised
rather than bypassed.

Per mode (`user`, `project`), in order:

1. **Clear** — delete launcher, jar cache, and every config path this mode writes.
2. **Assert cleared** — all of them absent. This is what makes the test honest: a stale install from
   a previous run must not be able to make a later step pass.
3. **Install** — run the mode's one-liner.
4. **Assert installed** — launcher present and executable; jar present; each expected config file
   exists, parses as its format, and carries the exact expected `command` and `args`.
5. **Assert it answers** — drive `claude -p`, `codex exec` and `agy` headless with a prompt that
   forces an MCP call against the fixture, and assert the distinctive fixture symbol comes back.
6. **Assert idempotent** — re-run the same install; configs are byte-identical, no duplicate
   `scala-semantic` entry, and an unrelated MCP server planted in the config beforehand survives.
7. **Teardown** — remove the temp `HOME` and fixture; assert removed.

Plus one negative case, which is the direct test of Decision 4: with the user-mode install in place,
launch the server with cwd set to a non-Scala temp directory. Assert the JSON-RPC handshake
**succeeds** and `tools/list` returns the full tool set, and that a subsequent tool call returns the
`could not detect a Scala project root` error rather than an empty result.

The test drives clients through their headless flags rather than through a TTY, so it can assert on
stdout. Where a client cannot be found on `PATH`, that client's step is reported as skipped and the
run continues — a missing `agy` must not mask a real failure in the `claude` path.

## Files touched

| file | change |
|---|---|
| `scripts/scalasemantic-mcp.sh` | self-install when `$0` unresolvable; `--project` flag; install dirs; stays ≤ ~100 lines |
| `scripts/install.sh` | **deleted** |
| `scripts/scalasemantic-mcp.scala` | **deleted** (duplicates `launcher/`) |
| `scripts/test-install.sc` | **new**, written first |
| `launcher/…/LauncherSetup.scala` | `Scope` enum, `Options.scope`, `--scope` parsing, skip project-only steps under user scope |
| `launcher/…/LauncherClientConfigs.scala` | `Target.userPath: Option[String]`, scope-aware target resolution, skip-with-note for `None` |
| `launcher/…/LauncherMessages.scala` | usage text for `--scope`, skipped-client note |
| `launcher/…/LauncherClientConfigsSuite.scala` | user-scope target resolution, skip behaviour |
| `mcp/…/Main.scala` | stop `sys.exit(1)` on unresolved root; pass the state to `Mcp.serve` |
| `mcp/…/Mcp.scala` | unresolved-root state → tool-call error |
| `mcp/…/ProjectRootDiscoverySuite.scala` | cover the call-time error path |
| `docs/adr/0004-…md` | **new** — amends 0003 §3, supersedes the two-script layout |
| `docs/getting-started/quickstart.md`, `integration.md` | two one-liners; user scope documented as default |
| `README.md` | quick-setup one-liner updated |
| `scripts/smoke-test-scripts.sc` | drop references to the deleted scripts |

## Risks

- **Piping a script that self-installs is a pattern users are right to distrust.** Mitigation: the
  script is small enough to read in full before running, and the docs show the download-then-inspect
  form alongside the pipe form.
- **A wrong user-config path silently no-ops.** Mitigation: Decision 3's `None`-means-skip rule, and
  step 5 of the test, which proves the client actually reaches the server rather than merely that a
  file was written.
- **The test depends on three external CLIs whose headless flags may change.** Mitigation: it is
  local-only and skips missing clients loudly; the config-level assertions (steps 4 and 6) still
  hold without any client.
- **Existing users' cached jars are orphaned** by the cache-directory move. Mitigation: one extra
  download; note it in the release notes.

## Open questions

None blocking. The three unverified user-config paths in Decision 3 are resolved by reading client
documentation during implementation, and Decision 3 specifies exactly what happens if one cannot be
confirmed.
