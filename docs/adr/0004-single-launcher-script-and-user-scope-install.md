# ADR 0004 — One launcher script, user-scope install by default, call-time root failure

- **Status:** accepted
- **Date:** 2026-09-05
- **Amends:** ADR-0003 §3 (fail-closed root discovery moves from startup to tool-call time)
- **Supersedes:** the two-script install layout (`scripts/install.sh` + `scripts/scalasemantic-mcp.sh`)
- **Applies to:** `scripts/scalasemantic-mcp.sh`, `scripts/test-install.sc`, `LauncherSetup`,
  `LauncherClientConfigs`, `LauncherMessages`, `Main`, `Mcp`, `docs/getting-started/*`

## Context

Installing took two steps that never ended: run `scripts/install.sh` once per machine, then
`scalasemantic-mcp setup` once per Scala project, forever. Install logic also existed in three
places:

- `scripts/install.sh` — shell bootstrap that printed a config snippet.
- `scripts/scalasemantic-mcp.sh` — shell launcher: jar cache plus `exec java -jar`.
- `scripts/scalasemantic-mcp.scala` — a 45 KB scala-cli script re-implementing client-config
  writing, SemanticDB configuration and the guard hook.

The third duplicated the `launcher/` module, which already owns that logic in Scala and already
ships inside the fat jar (`mcp.moduleDeps = Seq(analysis, build.launcher)`). The two copies had
already drifted: the scala-cli one emitted `scala-cli run --dependency … -- .` argv where the module
emits `serve .`.

Per-project registration is also weaker than it looks as a team-onboarding story. A committed
`.mcp.json` names a `command` the teammate does not have, so cloning the repo yields a broken server
entry, not a working one. Project scope shares *config*, never the *binary*.

## Decision

**1. One script.** `scripts/install.sh` and `scripts/scalasemantic-mcp.scala` are deleted.
`scripts/scalasemantic-mcp.sh` is both launcher and installer. When `$0` does not resolve to a
readable file containing this script — i.e. it was piped to `sh` — it downloads itself to the
install location, `chmod +x`, and re-execs with an explicit mode. That self-install is the entire
reason a second bootstrap script existed. Install *decisions* stay in Scala; the script knows
nothing about clients, config formats or build tools.

**2. Two one-liners, user scope by default.**

```sh
curl -fsSL …/scripts/scalasemantic-mcp.sh | sh                 # user scope
curl -fsSL …/scripts/scalasemantic-mcp.sh | sh -s -- --project # project scope, from the repo root
```

| | launcher | jar | configs | `command` |
|---|---|---|---|---|
| user (default) | `$HOME/.local/bin/scalasemantic-mcp` | `$HOME/.local/share/scalasemantic-mcp/` | user-level | absolute |
| `--project` | `./scalasemantic-mcp.sh`, committable | same shared directory | project-level | relative (ADR-0002) |

User-scope configs keep the **absolute** launcher path deliberately: GUI-launched MCP clients often
spawn without `~/.local/bin` on `PATH`, where a bare `scalasemantic-mcp` fails with an opaque
ENOENT. Project scope keeps relativizing, so a committed config survives a clone.

The jar moved from `${XDG_CACHE_HOME:-$HOME/.cache}/scalasemantic-mcp` to
`$HOME/.local/share/scalasemantic-mcp` (override: `SCALASEMANTIC_HOME`). A downloaded release jar is
installed data, not a cache; an ~88 MB artifact must not be evicted by a cache cleaner.

**3. `--scope user|project`, and no guessed user paths.** `LauncherSetup.Options` gains `scope` and
`home`; `LauncherClientConfigs.Target` gains `userPath: Option[String]`. `None` means the client has
no known user-level MCP config, and `--scope user` skips it with a printed note. Under user scope
the project-only steps (SemanticDB configuration, the rules file, the guard hook) are skipped —
there is no project to configure.

| client | project path | user path |
|---|---|---|
| claude | `.mcp.json` | `~/.claude.json` |
| codex | `.codex/config.toml` | `~/.codex/config.toml` |
| gemini | `.gemini/settings.json` | `~/.gemini/settings.json` |
| continue | `.continue/config.yaml` | `~/.continue/config.yaml` |
| antigravity | `.agents/mcp_config.json` | `~/.gemini/config/mcp_config.json` |
| cline | `.cline/mcp.json` | none |
| roo | `.roo/mcp.json` | none |

Claude's is the one that does not mirror its project path. Antigravity's global file is shared by
its IDE and CLI and lives under `.gemini`, not `.antigravity` — per
<https://antigravity.google/docs/cli/mcp/>. Cline and Roo ship `None`: both keep global MCP settings
inside VS Code's `globalStorage`, an OS-specific path rather than one `$HOME`-relative location, and
for the Cline CLI the docs and the code disagree on the path
(<https://github.com/cline/cline/issues/11671>). A guessed path is worse than none — it writes a
file nobody reads while telling the user they are installed.

`Options.home` reads `$HOME` before `user.home`. The JVM derives `user.home` from the OS account,
not the environment, so a sandboxed run could not redirect user-scope writes; during development
this made the install test write into the developer's real home directory.

**4. Root discovery fails at call time, not startup (amends ADR-0003 §3).** A missing build marker
no longer exits the process. The server completes the handshake and lists its full tool set;
each tool call then returns ADR-0003's error text as a tool error. `set_workspace_root` and
`get_workspace_root` stay live — they are the in-band fix. The tool list is built against an empty
scratch directory so names and JSON schemas match a healthy server exactly.

ADR-0003's goal — never a confident `count: 0` from a silently wrong index — is preserved, because
the server still refuses to index an unvalidated cwd. Only the reporting channel changes. A process
exit code was the right channel for a per-project install and the wrong one for a global binary:
with the server registered user-wide, every Python, Node, Go or docs repository on the machine would
otherwise show a permanent "failed to connect" badge.

**5. A local end-to-end install test.** `scripts/test-install.sc` runs per mode: clear, assert
cleared, install through a pipe (exercising self-install), assert launcher/jar/config contents,
assert the installed server answers over raw JSON-RPC, drive real LLM clients, assert a re-install is
byte-identical with no duplicate entry, assert a non-Scala directory connects and explains itself,
tear down. Not in CI: it needs authenticated client binaries.

Its fixture is a Scala CLI project compiled with `--semanticdb` and an explicit **visible**
`--semanticdb-targetroot`; Scala CLI's default puts SemanticDB under the hidden `.scala-build/`,
which `SemanticIndex` skips while walking. Without that the fixture would have no index and every
lookup would answer "not found" no matter how correct the install was.

## Client findings from building that test

- **claude** — works headless with `--allowedTools=…` (the `=`form; the list form swallows the
  prompt argument).
- **dsh (DeepSeek Harness)** — works, driven as
  `dsh --profile headless --patch <overlay> "<prompt>"`. It configures MCP through a **cordis patch
  overlay**, one server per plugin entry, not through any `mcpServers` map, and its `headless`
  profile omits the MCP plugin entirely. So the installer cannot currently target dsh; the test
  proves the launcher works under it, not that the install configured it. Real dsh support is
  follow-up work.
- **codex** — temporarily disabled in the test (no API credits). Re-enabling is not just
  uncommenting: `codex --help` documents config as loaded from `~/.codex/config.toml`, overridable
  only via `$CODEX_HOME`, with no project-local `.codex/config.toml` discovery. A run duly ignored
  the fixture's config and reached for the developer's global servers. **Project-scope Codex support
  is therefore a no-op today** — a pre-existing defect this work uncovered rather than caused, and
  worth its own issue.
- **agy** — disabled. Headless mode never consults `permissions.allow` in any scope
  (<https://github.com/google-antigravity/antigravity-cli/issues/548>, open), so no rule set can
  authorise its tool calls and only `--dangerously-skip-permissions` works.
  `seedAgyPermissions` is kept, unused, ready for when that lands.

## Alternatives considered

**Keep project scope as the default.** Rejected: it costs a step in every repository forever, and
its one advantage — a shareable committed config — does not survive contact with a teammate who
lacks the binary.

**Register globally but keep ADR-0003's startup exit.** Rejected: that is precisely the combination
that puts a broken server entry in every non-Scala repository on the machine.

**Guess the missing user-config paths for Cline and Roo.** Rejected — see Decision 3.

**Keep the jar in `$XDG_CACHE_HOME`.** Rejected: cache cleaners may evict it, and re-downloading
~88 MB on a cold MCP connect races the client's connection timeout.

## Consequences

- Installing is one command; nothing to run per project unless the user wants a committed config.
- A user-scope registration is inert, not broken, in non-Scala repositories.
- Existing users re-download the jar once, into the new directory.
- Cline and Roo users must use a project install, or add the server by hand.
- Codex project-scope configs are written but not read; that gap is now documented rather than
  silently assumed to work.
- `scripts/test-install.sc` must be run by hand in both modes before cutting a release; `./mill
  prePush` does not cover it.
- `docs/getting-started/*`, the README and this ADR describe one install flow; a future change to
  the one-liners must update all of them together.
