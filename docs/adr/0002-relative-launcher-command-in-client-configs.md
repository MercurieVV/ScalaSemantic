# ADR 0002 — Relative launcher path in client configs, absolute as fallback

- **Status:** accepted
- **Date:** 2026-08-08
- **Applies to:** `scalasemantic-mcp setup` (jar launcher, `LauncherClientConfigs.scala`)

## Context

`setup` writes the MCP server's `command` into each client's config (`.mcp.json`,
`.codex/config.toml`, `.gemini/settings.json`, `.cline/mcp.json`, `.roo/mcp.json`,
`.continue/config.yaml`, `.agents/mcp_config.json`). The value comes from
`LauncherSetup.Options.command`, which defaults to `sys.env.getOrElse("SCALASEMANTIC_LAUNCHER",
"scalasemantic-mcp")`.

`scripts/scalasemantic-mcp.sh` always exports `SCALASEMANTIC_LAUNCHER` as its own **absolute**
path (`SELF=$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")`) before invoking `setup`.
The documented quickstart flow (`docs/getting-started/quickstart.md`) has the user curl this
script straight into the project root and run `./scalasemantic-mcp.sh setup`. Result: every
generated config carried an absolute path baked to wherever that particular checkout happened to
sit (e.g. `/Users/x/project/scalasemantic-mcp.sh`), even though the script lives inside the
project it configures.

This repo's own `.mcp.json` (hand-written, not generated) already uses a relative path —
`./scripts/scalasemantic-mcp.sh` — and works, because Claude Code launches the server with cwd =
project root.

## Decision

`LauncherClientConfigs.write` rewrites an absolute `command` to a project-relative one
(`./relative/path`) whenever that path resolves inside the project root being configured. Any
other value — a bare PATH command (`scalasemantic-mcp`), an already-relative value, or an absolute
path outside the project — is left untouched.

Rationale: a launcher script living inside the project should be addressed relative to the project
it configures. Relative survives the project directory being cloned/moved/shared (config can be
committed to git); absolute breaks on every such move and requires re-running `setup` per
teammate/machine.

## Alternatives considered

**Always emit absolute (status quo).** Robust to whatever cwd a given MCP client happens to launch
the server with, but ties the generated config to one specific checkout path — bad for a
git-committed `.mcp.json` shared across a team, and the actual bug reported (skreeep2 config
pointed at one machine's absolute path).

**Always emit relative when possible, never fall back to absolute.** Rejected: some MCP clients
are not guaranteed to spawn the server with cwd = project root; a bare relative path would break
silently there with no absolute fallback to catch it.

## Consequences

- Generated configs for a project-local launcher script are now portable across clones, matching
  this repo's own `.mcp.json`.
- If a client is later found to spawn the server with a different cwd, the fix is either
  `--command <absolute-path>` at setup time, or teaching `relativizeCommand` to special-case that
  client's `Target`.
- Bare PATH-based installs (`scripts/install.sh` → `~/.local/bin/scalasemantic-mcp`) are
  unaffected — the command is never absolute in that flow, so no relativization applies.
