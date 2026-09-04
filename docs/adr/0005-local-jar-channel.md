# ADR 0005 — Local jar channel: `./mill installLocal`

- **Status:** accepted
- **Date:** 2026-09-05
- **Extends:** [ADR-0004](0004-single-launcher-script-and-user-scope-install.md) — adds a second
  jar channel to the launcher it defines, without changing its install flow
- **Applies to:** `scripts/scalasemantic-mcp.sh`, `build.mill` (`installLocal`),
  `scripts/smoke-tests-local-run/test-local-channel.sc`, `docs/getting-started/integration.md`

## Context

Every install path ends at a GitHub release: the launcher resolves the newest tag, downloads the
jar into the user data directory, and background-fetches newer releases on later starts. A change
to `core/`, `analysis/` or `mcp/` therefore reaches the developer's own MCP clients only after a
tag, a CI publish and a fetch.

The `SCALASEMANTIC_JAR` override exists, and the smoke tests use it, but it lives in one client
config for one project. It is neither global nor durable, and it goes stale silently.

## Decision

A cached jar whose filename ends in `-local.jar` marks the local development channel.

1. `./mill installLocal` builds `mcp.assembly`, installs the launcher to `${BIN_DIR:-~/.local/bin}`,
   replaces the single `*-local.jar` in `${SCALASEMANTIC_HOME:-~/.local/share/scalasemantic-mcp}`,
   and then runs `java -jar <jar> install --scope user` so client configuration keeps going through
   the `launcher/` module rather than a second implementation. `--skip-clients` stops after the jar
   and launcher.
2. While a `*-local.jar` exists, the launcher selects it and performs no release resolution and no
   background fetch. It wins **regardless of mtime**: a release downloaded after it is newer, and
   the point of the channel is that no download can take the slot back. The version in the filename
   is a label for humans; `newest_local()` is what selects it.
3. `scalasemantic-mcp --use-release` deletes every `*-local.jar` and exits, returning the machine to
   the release channel. It is idempotent and leaves cached releases untouched.

Exactly one channel is active at a time, and the precedence is total:
`SCALASEMANTIC_JAR` → `*-local.jar` → `SCALASEMANTIC_VERSION`/release → newest cached release.

`installLocal` reads `Task.env`, not `sys.env`: the Mill daemon outlives any one invocation, so
`sys.env` is whatever environment happened to start it rather than the caller's.

## Consequences

- Edit, `./mill installLocal`, restart clients: every project on the machine runs the new build.
- The local channel does not expire on its own. That is the point — an auto-update must not
  silently revert a developer mid-debug — but a forgotten local jar keeps a machine off releases
  indefinitely. `--use-release` is the documented exit, and `installLocal` prints it on every run.
- The launcher never invokes a build tool, so server startup stays `java -jar`.
- One more thing the launcher must get right in POSIX `sh`, covered by `test-local-channel.sc`,
  whose launcher-only half runs in `./mill smokeTest` (its `with-mill` half re-enters Mill and so
  is run by hand).

## Alternatives considered

**Coursier.** `./mill mcp.publishLocal` into `~/.ivy2/local` plus `cs launch -r ivy2Local` resolves
transitive dependencies correctly and would work. Rejected for two reasons. Merging a coursier
channel with the home-directory channel has no total order: an Ivy/Maven semver and a file mtime are
not comparable, so `0.4.2` from Central versus a thirty-second-old local build of `0.4.2` requires
an invented rule, which becomes a rule to debug when the wrong build answers a tool call. And
resolution would land on every server start — an MCP server starts per client launch per project,
where `java -jar` needs neither network nor resolution. Coursier remains reasonable as a future
*alternative release* channel (`cs install` in place of the curl one-liner); it would replace the
release channel, never merge with the local one.

**Auto-rebuild on server start.** Rejected: it puts a Mill invocation on the startup path of every
client launch and makes a JDK-plus-build-tool a runtime requirement of the server.

**A pin file in the data directory** (`local-jar` naming the path). Equivalent in effect, but it
adds a second source of truth that can disagree with what is on disk. The filename suffix cannot.
