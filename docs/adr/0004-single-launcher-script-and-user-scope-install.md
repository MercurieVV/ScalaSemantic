# ADR 0004 — One launcher script, user-scope install by default, call-time root failure

- **Status:** accepted
- **Date:** 2026-09-05
- **Amends:** [ADR-0003](0003-global-install-default-and-root-discovery.md) — its install decisions,
  and how a failed root discovery is reported
- **Applies to:** `scripts/scalasemantic-mcp.sh`, `scripts/smoke-tests-local-run/test-install.sc`, `LauncherSetup`,
  `LauncherClientConfigs`, `Main`, `Mcp`, `docs/getting-started/*`

## Context

Installing took two steps that never ended: `install.sh` once per machine, then `setup` once per
project, forever. Install logic lived in three places — `scripts/install.sh`,
`scripts/scalasemantic-mcp.sh`, and a 45 KB `scripts/scalasemantic-mcp.scala` that re-implemented
the `launcher/` module already shipping inside the fat jar. The two copies had drifted: the
scala-cli one emitted `scala-cli run --dependency … -- .` where the module emits `serve .`.

Per-project registration is also a weaker onboarding story than it looks: a committed `.mcp.json`
names a `command` the teammate does not have, so cloning yields a broken entry. Project scope shares
*config*, never the *binary*.

## Decision

**1. One script.** `install.sh` and `scalasemantic-mcp.scala` are deleted.
`scripts/scalasemantic-mcp.sh` is launcher *and* installer: when `$0` does not resolve to a readable
file containing the script — i.e. it was piped to `sh` — it downloads itself to the install location
and re-execs with an explicit mode. That self-install is the whole reason a second bootstrap script
existed. Install *decisions* stay in Scala; the script knows nothing about clients or config formats.

**2. Two one-liners, user scope by default.**

```sh
curl -fsSL …/scripts/scalasemantic-mcp.sh | sh                 # user scope
curl -fsSL …/scripts/scalasemantic-mcp.sh | sh -s -- --project # project scope, from the repo root
```

| | user (default) | `--project` |
|---|---|---|
| launcher | `$HOME/.local/bin/scalasemantic-mcp` | `./scalasemantic-mcp.sh`, committable |
| jar | `$HOME/.local/share/scalasemantic-mcp/` | same shared directory |
| configs | per-user | per-project |
| `command` | absolute | relative ([ADR-0002](0002-relative-launcher-command-in-client-configs.md)) |
| SemanticDB setup, rules file, guard hook | no | yes |

User scope keeps the **absolute** path deliberately: GUI-launched clients often spawn without
`~/.local/bin` on `PATH`, where a bare `scalasemantic-mcp` fails with an opaque ENOENT.

The jar moved from `${XDG_CACHE_HOME:-$HOME/.cache}` to `$HOME/.local/share/scalasemantic-mcp`
(override `SCALASEMANTIC_HOME`): a downloaded release jar is installed data, not a cache, and an
~88 MB artifact must not be evicted by a cache cleaner.

**3. `--scope user|project`, and never a guessed path.** `Target` gains
`userPath: Option[String]`; `None` means the client has no known user-level config and `--scope user`
skips it with a printed note. A guessed path is worse than none — it writes a file nobody reads while
telling the user they are installed.

| client | project | user |
|---|---|---|
| claude | `.mcp.json` | `~/.claude.json` |
| codex | `.codex/config.toml` | `~/.codex/config.toml` |
| gemini | `.gemini/settings.json` | `~/.gemini/settings.json` |
| continue | `.continue/config.yaml` | `~/.continue/config.yaml` |
| antigravity | `.agents/mcp_config.json` | `~/.gemini/config/mcp_config.json` |
| cline, roo | `.cline/mcp.json`, `.roo/mcp.json` | none |

Claude's is the one that does not mirror its project path. Antigravity's global file is shared by its
IDE and CLI and lives under `.gemini` ([docs](https://antigravity.google/docs/cli/mcp/)). Cline and
Roo keep global MCP settings inside VS Code's `globalStorage` — an OS-specific path, not one
`$HOME`-relative location — and for the Cline CLI the docs and the code disagree
([cline#11671](https://github.com/cline/cline/issues/11671)).

`Options.home` reads `$HOME` before `user.home`: the JVM derives `user.home` from the OS account, not
the environment, so a sandboxed run could not redirect user-scope writes. During development this
made the install test write into the developer's real home directory.

**4. Root discovery fails at call time, not startup.** A missing build marker no longer exits. The
server handshakes and lists its full tool set; each call then returns ADR-0003's error text as a tool
error. `set_workspace_root`/`get_workspace_root` stay live — they are the in-band fix. The tool list
is built against an empty scratch directory so names and schemas match a healthy server exactly.

```
$ cd ~/some-python-app && scalasemantic-mcp serve .
→ initialize: ok, tools/list: 30 tools
→ find_symbol: "could not detect a Scala project root at or above '…' "   (exit 0)
```

ADR-0003's goal — never a confident `count: 0` from a wrong index — is preserved; only the reporting
channel changes. A process exit code was right for a per-project install and wrong for a global
binary: otherwise every Python, Node or docs repo shows a permanent "failed to connect" badge.

**5. A local end-to-end install test.** `scripts/smoke-tests-local-run/test-install.sc`, per mode: clear → assert cleared →
install through a pipe (exercising self-install) → assert launcher/jar/config contents → assert the
server answers over raw JSON-RPC → drive real LLM clients → assert a re-install is byte-identical
with no duplicate entry → assert a non-Scala directory connects → tear down. Not in CI: it needs
authenticated client binaries.

Its fixture is a Scala CLI project compiled with `--semanticdb` and an explicit **visible**
`--semanticdb-targetroot`. Scala CLI's default puts SemanticDB under the hidden `.scala-build/`,
which `SemanticIndex` skips while walking — without this the fixture has no index and every lookup
answers "not found" no matter how correct the install is.

## Client findings from building that test

- **claude** — works headless with `--allowedTools=…` (the `=`form; the list form swallows the prompt).
- **dsh (DeepSeek Harness)** — works as `dsh --profile headless --patch <overlay> "<prompt>"`. It
  configures MCP through a **cordis patch overlay**, one server per plugin entry, not an
  `mcpServers` map, and its `headless` profile omits the MCP plugin entirely. The installer cannot
  target dsh yet; the test proves the launcher works under it, not that the install configured it.
- **codex** — disabled in the test (no credits). Config loads from `~/.codex/config.toml`,
  overridable only via `$CODEX_HOME`, with **no project-local `.codex/config.toml` discovery** — a
  run duly ignored the fixture's config and reached for the developer's global servers. So
  **project-scope Codex support is a no-op today**: a pre-existing defect this work uncovered.
- **agy** — disabled: headless mode never consults `permissions.allow` in any scope
  ([antigravity-cli#548](https://github.com/google-antigravity/antigravity-cli/issues/548), open), so
  only `--dangerously-skip-permissions` works. `seedAgyPermissions` is kept, unused, ready for the fix.

## Rejected

- **Project scope as the default** — a step in every repo forever, and its one advantage (a shareable
  committed config) does not survive a teammate who lacks the binary.
- **Global registration keeping ADR-0003's startup exit** — precisely the combination that puts a
  broken entry in every non-Scala repo.
- **Guessing Cline's and Roo's user paths** — see Decision 3.
- **Leaving the jar in `$XDG_CACHE_HOME`** — eviction means re-downloading ~88 MB on a cold connect,
  racing the client's timeout.

## Consequences

- Installing is one command; nothing per project unless a committed config is wanted.
- A user-scope registration is inert, not broken, in non-Scala repositories.
- Existing users re-download the jar once, into the new directory.
- Cline and Roo users need a project install, or a manual entry.
- Codex project-scope configs are written but not read — documented rather than silently assumed.
- `scripts/smoke-tests-local-run/test-install.sc` must be run by hand in both modes before a release; `./mill prePush` does
  not cover it.
