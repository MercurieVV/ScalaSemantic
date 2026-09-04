# Quickstart

Shortest path for any project.

1. **Install the launcher once, shared across all your projects** (needs only `java`):
   ```sh
   curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/install.sh | sh
   ```
   Then, from the root of the project you want to analyze:
   ```sh
   scalasemantic-mcp setup
   ```
   (Prefer a version pinned inside the project instead of a shared machine-wide binary? See
   [Integration](integration.md) option A/B for the per-project script flow.)
2. **Compile the project**:
   ```sh
   sbt compile
   ```
3. **Refresh live-buffer classpath metadata after dependency/build changes**:
   ```sh
   sbt scalaSemanticWriteClasspath
   ```

For manual configurations or other integration options (Scala CLI remote script, plain `java -jar`), see [Integration](integration.md).

## Claude Code: the guard hook

`setup` additionally installs `.claude/hooks/scala-semantic-guard.sh` and registers it as a
`PreToolUse` hook, so text tools (`Read`, `Grep`, `Glob`, and shell `grep`/`rg`/`cat`/`sed`/…) are
**denied** on `.scala` files and the agent is told which MCP tool to use instead. It fails open when
no `*.semanticdb` has been emitted yet, when the MCP server is not configured for the project, or
when neither `jq` nor `python3` is available. A shell command carrying
`# semantic-fallback: <reason>` is always allowed, and appended to `.claude/semantic-fallback.log`.

Opt out with `./scalasemantic-mcp.sh setup --no-guard`. Background:
[ADR 0001](../adr/0001-claude-code-guard-hook.md).
