# Integration

An MCP **stdio** server is spawned by the MCP client (for example Claude Code, Codex, Gemini CLI, or
another coding agent), which owns its lifecycle — you don't run it as a daemon. Integrating means two
things: make the project emit SemanticDB, and register a launch command scoped to that project's root.
Because the unit is a plain process, the same approach works from any build tool.

The server speaks newline-delimited JSON-RPC 2.0 on **stdout**; **stderr** carries only launcher
download chatter. The server's own diagnostic logging is **off by default** (nothing is written) and,
when enabled, goes to a *file* rather than the streams — see [Logging](#logging). Point it at a
directory that contains emitted `*.semanticdb` files (the target project must be compiled with
SemanticDB enabled).

## Prerequisite for every option: SemanticDB on the target project

The server *reads* SemanticDB; it does not generate it. The project you want to analyze must be
compiled with it enabled. In sbt:

```scala
semanticdbEnabled := true
```

For Mill/Gradle/scalac, enable the SemanticDB compiler plugin the equivalent way, then compile. (The
sbt plugin in Option A does this step for you.)

The only other requirement on the user's machine is a **JVM** (`java` on PATH) — no coursier, no sbt.

## Distribution: the fat jar on GitHub Releases

Each `vX.Y.Z` tag builds a self-contained fat jar (`mcp/assembly`, all deps bundled) and CI attaches
it to the matching [GitHub Release](https://github.com/MercurieVV/ScalaSemantic/releases). That single
file is what every launch option below runs with `java -jar`.

## Three ways to launch

All three end at the same place: MCP client configuration that runs the server with the project root
as its argument. Pick by how much you want automated.

| | A — sbt plugin | B — auto-download script | C — plain `java -jar` |
|---|---|---|---|
| Get the jar | launcher downloads + caches it | script downloads + caches it | you download it once |
| Write client config | `sbt mcpClientConfig` writes + merges it | by hand (point at script) | by hand (point at `java`) |
| Enable SemanticDB | plugin does it | you (one line) | you (one line) |
| Stays up to date | yes — pulls latest each launch | yes — pulls latest each launch | manual re-download |
| Works with | sbt (1 and 2) | any OS / build tool | any OS / build tool |

### Option A — sbt plugin (recommended; writes the client config for you)

`io.github.mercurievv:sbt-scalasemantic-mcp` is cross-published for sbt 1 and sbt 2. The same
`addSbtPlugin` line works in both; sbt resolves the matching plugin artifact for your build. The
minimal host build is one line:

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "@VERSION@")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

On the rendered docs site `@VERSION@` is filled with the latest published release at build time. If
you are reading the raw source, take the current version from the
[Maven Central artifact](https://central.sonatype.com/artifact/io.github.mercurievv/sbt-scalasemantic-mcp_2.12_1.0)
or the [latest GitHub release](https://github.com/MercurieVV/ScalaSemantic/releases/latest).

The plugin adds two tasks:

- `sbt mcpClientConfig [client]` — generates MCP config for your client format (claude, codex, gemini, antigravity, cline, roo, continue, generic-json, or all). Also auto-generates `SCALA_SEMANTIC_RULES.md` for agents.
- `sbt mcpRun` — runs the server locally for testing.

Just run `sbt enablePlugins(ScalaSemanticMcpPlugin)` + `sbt mcpClientConfig`, and the launcher script automatically downloads and caches the server jar on first spawn. To pin a specific jar path instead, override `mcpServerCommand` (e.g., `Seq("java", "-jar", "/abs/path/to/scalasemantic-mcp.jar")`).

Choose the generated client format with `mcpClient`:

```scala
mcpClient := "claude"       // default: Claude Code .mcp.json-style JSON
mcpClient := "codex"        // Codex config.toml
mcpClient := "gemini"       // Gemini CLI settings JSON
mcpClient := "antigravity"  // Antigravity CLI/IDE mcp_config.json
mcpClient := "cline"        // Cline MCP JSON
mcpClient := "roo"          // Roo Code MCP JSON
mcpClient := "continue"     // Continue config.yaml
mcpClient := "generic-json" // other MCP clients using the standard mcpServers JSON shape
mcpClient := "all"          // generate configurations for all supported LLM clients
```

The plugin only enables SemanticDB and shells out to `mcpServerCommand` — it never links against the
Scala 3.8.4 server, which is why it is sbt-1/2 and build-tool portable.

#### What `mcpClientConfig` writes

The task appends the project root, classpath file, and logging flags to `mcpServerCommand`, then writes the result into a project-local file (merging with existing entries):

| `mcpClient` | file |
|---|---|
| `claude`, `generic-json` | `.mcp.json` |
| `codex` | `.codex/config.toml` |
| `gemini` | `.gemini/settings.json` |
| `antigravity` | `.agents/mcp_config.json` |
| `cline` | `.cline/mcp.json` |
| `roo` | `.roo/mcp.json` |
| `continue` | `.continue/config.yaml` |

Example for `claude` (default):

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "~/.local/bin/scalasemantic-mcp",
      "args": ["/abs/path/to/this/project", "~/.local/bin/scala-semantic-classpath.txt", "--log", "--log-output"]
    }
  }
}
```

(Logging flags `--log --log-output` are optional; see [Logging](#logging). Drop them for silent mode.)

Client-specific format examples:

**codex** (`.codex/config.toml`):
```toml
[mcp_servers.scala-semantic]
command = "~/.local/bin/scalasemantic-mcp"
args = ["/abs/path/to/this/project", "~/.local/bin/scala-semantic-classpath.txt", "--log", "--log-output"]
startup_timeout_sec = 60
tool_timeout_sec = 60
```

**gemini** (`.gemini/settings.json`):
```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "~/.local/bin/scalasemantic-mcp",
      "args": ["/abs/path/to/this/project", "~/.local/bin/scala-semantic-classpath.txt", "--log", "--log-output"],
      "timeout": 60000
    }
  }
}
```

**antigravity** (`.agents/mcp_config.json`), **cline** (`.cline/mcp.json`), **roo** (`.roo/mcp.json`), **continue** (`.continue/config.yaml`) — similar structures with client-specific fields. See [ScalaSemanticMcpPlugin.scala](https://github.com/MercurieVV/ScalaSemantic/blob/master/sbt-plugin/src/main/scala/com/github/mercurievv/scalasemantic/sbtplugin/ScalaSemanticMcpPlugin.scala) for generation logic.

### Option B — auto-download launcher

Use the launcher script ([Linux/macOS](https://github.com/MercurieVV/ScalaSemantic/blob/master/scripts/scalasemantic-mcp.sh) or [Windows](https://github.com/MercurieVV/ScalaSemantic/blob/master/scripts/scalasemantic-mcp.ps1)) — it downloads and caches the jar automatically from GitHub Releases. Install it to PATH:

```sh
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/install.sh | sh
# Caches to ~/.cache/scalasemantic-mcp; serves instantly on next launch
```

Use coursier if available (`cs launch …`), else downloads the fat jar. Downloads are resumable, and the launcher prefetches the jar so the first MCP connection hits a warm cache. Pin a version with `SCALASEMANTIC_VERSION=vX.Y.Z` (default is latest).

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "/abs/home/.local/bin/scalasemantic-mcp",
      "args": ["/abs/path/to/project-to-analyze"]
    }
  }
}
```

### Option C — plain `java -jar`

Download `scalasemantic-mcp.jar` from the
[latest release](https://github.com/MercurieVV/ScalaSemantic/releases/latest) and reference it
directly — no script in between:

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

> A bare `sbt runMain` writes its own build logs to stdout and corrupts the JSON-RPC stream — always
> launch the jar (or the script that wraps it) so stdout carries only protocol messages. To build the
> jar locally instead of downloading: `sbt "mcp/assembly"`.

## Logging

The server is **silent by default** — no log file is created. Turn it on with flags (appended to the
`.mcp.json` `args`, after the project root — the launcher forwards them to the server) or the matching
env vars:

| Flag | Env | Logs |
|---|---|---|
| `--log` | `SCALASEMANTIC_LOG=1` | a startup line + one line per tool call (name + arguments) |
| `--log-output` | `SCALASEMANTIC_LOG_OUTPUT=1` | additionally, each JSON-RPC response sent to the model; implies a sink, so it works on its own |

Flags are position-independent. The log file defaults to `<root>/scala-semantic-mcp.log`; override the
path with `SCALASEMANTIC_LOG_FILE`. Each line is timestamped and flushed, so `tail -f` shows it live.

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "/abs/home/.local/bin/scalasemantic-mcp",
      "args": ["/abs/path/to/project-to-analyze", "--log-output"]
    }
  }
}
```

## Manual stdio check

```sh
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_symbol","arguments":{"query":"Animal"}}}' \
 '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"class_hierarchy","arguments":{"symbol":"com/github/mercurievv/scalasemantic/fixtures/Animal#"}}}' \
 | java -jar scalasemantic-mcp.jar .
```

Expect four JSON-RPC responses on stdout (with logging off by default, nothing else is emitted; add
`--log`/`--log-output` to trace to a file). The `initialize` response carries an `instructions` field;
`find_symbol` turns the name `Animal` into the symbol string the `class_hierarchy` call then uses.
