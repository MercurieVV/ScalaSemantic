# Dynamic workspace roots

ScalaSemantic is a stdio MCP server. Most clients spawn the server once for a session and keep that
process alive while the agent works. If the agent later changes cwd, enters a git worktree, or runs a
subagent in a different directory, the already-running process does not automatically re-index the
new directory.

The generated MCP config therefore launches the server with `.` as its initial root, so a fresh
process indexes wherever the client actually starts it. For mid-session cwd changes, use the
stateful `set_workspace_root` tool and confirm with `get_workspace_root`.

This is a workaround for client lifecycle behavior, not a preferred replacement for protocol roots.
Claude Code has had related open issues around cwd/worktree drift and MCP subprocess lifecycle, for
example `anthropics/claude-code#42282`, `#32747`, `#27881`, and `#30906`. If clients reliably
reconnect stdio servers or send MCP roots notifications for these transitions, ScalaSemantic can wire
that protocol path in addition to the explicit tool.
