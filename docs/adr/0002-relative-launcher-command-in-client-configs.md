# ADR 0002 — Relative launcher path in project-scope configs, absolute as fallback

- **Status:** accepted; scope narrowed by [ADR-0004](0004-single-launcher-script-and-user-scope-install.md)
- **Date:** 2026-08-08
- **Applies to:** `LauncherClientConfigs.write` / `relativizeCommand`

## Context

`setup` writes the server's `command` into each client config. A project install puts the launcher
*inside* the project (`./scalasemantic-mcp.sh`), but the script exports its own **absolute** path as
`SCALASEMANTIC_LAUNCHER`. Generated configs therefore baked in whatever path that checkout happened
to sit at — which breaks on every clone, move or teammate, and is useless once committed.

## Decision

When the `command` resolves to a path **inside the project being configured**, rewrite it relative:

```jsonc
// before                                    // after
"command": "/Users/x/proj/scalasemantic-mcp.sh"  →  "command": "./scalasemantic-mcp.sh"
```

Everything else is left untouched: a bare `PATH` command (`scalasemantic-mcp`), an already-relative
value, or an absolute path *outside* the project. This works because MCP clients launch the server
with cwd = the project holding the config; absolute remains the fallback for clients that do not.

**ADR-0004 narrows this to project scope.** A user-scope config is never shared and is written to
`$HOME`, so it keeps the absolute launcher path — GUI-launched clients frequently spawn without
`~/.local/bin` on `PATH`, where a bare `scalasemantic-mcp` fails with an opaque ENOENT.

## Rejected

- **Always absolute** — robust to any cwd, but ties a committed config to one machine. This is the
  bug that prompted the ADR.
- **Always relative, no fallback** — breaks silently under any client that does not spawn with
  cwd = project root.

## Consequences

- A project-scope config is portable across clones and safe to commit.
- If a client is found to use a different cwd, fix it with `setup --command <absolute-path>` or
  special-case that client's `Target`.
