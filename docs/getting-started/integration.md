# Integration

ScalaSemantic is an MCP **stdio** server — the MCP client spawns it as a process and owns its lifecycle. Integrating means two things: make the target project emit SemanticDB, and register a launch command for that project root.

The server speaks newline-delimited JSON-RPC 2.0 on **stdout**. Diagnostic logging is **off by default** and, when enabled, goes to a file — never to stdout. See [Logging](#logging).

## Prerequisite: SemanticDB on the target project

The server reads SemanticDB; it does not generate it. The project must be compiled with SemanticDB enabled:

```scala
// build.sbt (sbt — also done automatically by the sbt plugin)
semanticdbEnabled := true
```

For Mill/Gradle/scalac, enable the SemanticDB compiler plugin equivalently and compile. The only machine requirement is a **JVM** (`java` on PATH).

Each release ships a self-contained fat jar attached to the [GitHub Release](https://github.com/MercurieVV/ScalaSemantic/releases). That jar is what all launch options run.

## Three ways to launch

| | A — sbt plugin | B — auto-download script | C — plain `java -jar` |
|---|---|---|---|
| Get the jar | launcher downloads + caches | script downloads + caches | you download once |
| Write client config | `sbt mcpClientConfig` | by hand | by hand |
| Enable SemanticDB | plugin does it | you add one line | you add one line |
| Stays up to date | yes | yes | manual |
| Works with | sbt 1 and 2 | any build tool | any build tool |

### Option A — sbt plugin (recommended)

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "@VERSION@")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

(`@VERSION@` is filled at doc-site build time. For the raw source, check [Maven Central](https://central.sonatype.com/artifact/io.github.mercurievv/sbt-scalasemantic-mcp_2.12_1.0) or [latest release](https://github.com/MercurieVV/ScalaSemantic/releases/latest).)

The plugin adds two tasks:

- `sbt mcpClientConfig [client]` — generates and merges MCP config into the project-local client config file, plus `SCALA_SEMANTIC_RULES.md`.
- `sbt mcpRun` — runs the server locally for testing.

The launcher downloads and caches the server jar on first spawn. To pin a specific jar, override `mcpServerCommand` in `build.sbt`.

#### Supported clients

```scala
mcpClient := "claude"       // default → .mcp.json
mcpClient := "codex"        // → .codex/config.toml
mcpClient := "gemini"       // → .gemini/settings.json
mcpClient := "antigravity"  // → .agents/mcp_config.json
mcpClient := "cline"        // → .cline/mcp.json
mcpClient := "roo"          // → .roo/mcp.json
mcpClient := "continue"     // → .continue/config.yaml
mcpClient := "generic-json" // → .mcp.json (generic mcpServers shape)
mcpClient := "all"          // writes all of the above
```

Or pass the client as a task argument: `sbt "mcpClientConfig gemini"`.

#### Generated config shape

All clients use the same server command; the file format differs. Example for `claude` (`.mcp.json`):

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "~/.local/bin/scalasemantic-mcp",
      "args": ["/abs/path/to/project", "~/.local/bin/scala-semantic-classpath.txt"]
    }
  }
}
```

`codex` uses TOML with `startup_timeout_sec`/`tool_timeout_sec` fields. `gemini` uses JSON with a `"timeout"` field. Others follow similar client-specific shapes. See [ScalaSemanticMcpPlugin.scala](https://github.com/MercurieVV/ScalaSemantic/blob/master/sbt-plugin/src/main/scala/com/github/mercurievv/scalasemantic/sbtplugin/ScalaSemanticMcpPlugin.scala) for all formats.

### Option B — auto-download launcher

```sh
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/install.sh | sh
```

Installs the launcher to `~/.local/bin/scalasemantic-mcp`. It downloads and caches the fat jar from GitHub Releases (uses coursier if available). Pin a version with `SCALASEMANTIC_VERSION=vX.Y.Z`.

Then register manually in your client config:

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "~/.local/bin/scalasemantic-mcp",
      "args": ["/abs/path/to/project-to-analyze"]
    }
  }
}
```

### Option C — plain `java -jar`

Download `scalasemantic-mcp.jar` from the [latest release](https://github.com/MercurieVV/ScalaSemantic/releases/latest):

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "java",
      "args": ["-jar", "/abs/path/to/scalasemantic-mcp.jar", "/abs/path/to/project-to-analyze"]
    }
  }
}
```

> Do not use `sbt runMain` — sbt writes build logs to stdout and corrupts the JSON-RPC stream. To build the jar locally: `sbt "mcp/assembly"`.

## Logging

Silent by default. Enable with flags appended to `args` (after the project root), or matching env vars:

| Flag | Env var | What it logs |
|---|---|---|
| `--log` | `SCALASEMANTIC_LOG=1` | startup line + one line per tool call |
| `--log-output` | `SCALASEMANTIC_LOG_OUTPUT=1` | also logs each JSON-RPC response |

Log file defaults to `<root>/scala-semantic-mcp.log`. Override with `SCALASEMANTIC_LOG_FILE`. Lines are timestamped and flushed — `tail -f` shows them live.

## Manual stdio check

```sh
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_symbol","arguments":{"query":"Animal"}}}' \
 '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"class_hierarchy","arguments":{"symbol":"com/github/mercurievv/scalasemantic/fixtures/Animal#"}}}' \
 | java -jar scalasemantic-mcp.jar .
```

Expect four JSON-RPC responses on stdout. The `initialize` response carries an `instructions` field; `find_symbol` turns `"Animal"` into the symbol string that `class_hierarchy` then uses.
