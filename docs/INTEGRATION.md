# Integration

An MCP **stdio** server is spawned by the MCP client (e.g. Claude Code), which owns its lifecycle —
you don't run it as a daemon. Integrating means two things: make the project emit SemanticDB, and
register a launch command scoped to that project's root. Because the unit is a plain process, the
same approach works from any build tool.

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

All three end at the same place: a `.mcp.json` entry that runs the server with the project root as its
argument. Pick by how much you want automated.

| | A — sbt plugin | B — auto-download script | C — plain `java -jar` |
|---|---|---|---|
| Get the jar | launcher downloads + caches it | script downloads + caches it | you download it once |
| Write `.mcp.json` | `sbt mcpClientConfig` generates it | by hand (point at script) | by hand (point at `java`) |
| Enable SemanticDB | plugin does it | you (one line) | you (one line) |
| Stays up to date | yes — pulls latest each launch | yes — pulls latest each launch | manual re-download |
| Works with | sbt (1 and 2) | any OS / build tool | any OS / build tool |

### Option A — sbt plugin (recommended; generates the `.mcp.json` for you)

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

The plugin enables SemanticDB and adds:

- `sbt mcpInstall` — writes the bundled auto-download launcher (Option B's script) into `target/`.
- `sbt mcpClientConfig` — runs `mcpInstall`, then prints the `.mcp.json` entry pointing at that script.
- `sbt mcpRun` — runs the server in the foreground (stdio) for manual testing.

So `enablePlugins` + `sbt mcpClientConfig` + paste is the whole setup — no jar to download by hand; the
written launcher fetches the server on first spawn (coursier if present, else the GitHub-Release fat
jar). To pin a fixed binary instead, override `mcpServerCommand`, e.g.
`mcpServerCommand := Seq("java", "-jar", "/abs/path/to/scalasemantic-mcp.jar")`.

The plugin only enables SemanticDB and shells out to `mcpServerCommand` — it never links against the
Scala 3.8.4 server, which is why it is sbt-1/2 and build-tool portable.

#### What `mcpClientConfig` generates

The task takes `mcpServerCommand`, then appends the project's base directory, the classpath file, and
the logging flags. With the default `mcpServerCommand` (the launcher `mcpInstall` writes) it emits:

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

`command` = `mcpServerCommand.head`; `args` = the rest of `mcpServerCommand`, then the auto-appended
project root and classpath file, then `--log --log-output` (the server's [logging](#logging) is off
unless these are present — drop them for the silent default). So overriding
`mcpServerCommand := Seq("java", "-jar", "/abs/scalasemantic-mcp.jar")` would instead yield
`"command": "java"` with `"-jar", "/abs/scalasemantic-mcp.jar"` leading those same trailing args.
Generation logic: [`ScalaSemanticMcpPlugin.scala`](../sbt-plugin/src/main/scala/com/github/mercurievv/scalasemantic/sbtplugin/ScalaSemanticMcpPlugin.scala).

### Option B — auto-download launcher

[`scripts/scalasemantic-mcp.sh`](../scripts/scalasemantic-mcp.sh) (Linux/macOS) and
[`scripts/scalasemantic-mcp.ps1`](../scripts/scalasemantic-mcp.ps1) (Windows) pick the best available
path automatically: if **coursier** (`cs`) is on PATH they `cs launch` the artifact from Maven Central
(resolves + caches like `npx`); otherwise they download the fat jar from the latest GitHub Release once
(cached under `~/.cache/scalasemantic-mcp` / `%LOCALAPPDATA%`) and run `java -jar`. Download chatter
goes to stderr; stdout stays pure JSON-RPC. Offline, they fall back to the newest cached jar. Pin a
version with `SCALASEMANTIC_VERSION=vX.Y.Z` (a real tag such as the latest release; default is newest).

The fat-jar download is **resumable and atomic**: an interrupted download (e.g. a connection killed by
the client's ~30s startup timeout) leaves a partial `.tmp` that the next launch *continues* rather than
restarting, and the cached jar is only swapped in once complete. So a slow first download converges
across the client's reconnect retries instead of looping.

Cold start is **instant once anything is cached**: when a (non-pinned) launch finds a cached jar it
serves that one *immediately* — zero download latency — and forks a detached background updater that
fetches the latest release for the **next** launch. So the very first launch on an empty cache still
blocks on the download (warm it ahead of time with `--prefetch`, or `sbt mcpClientConfig`, which
prefetches for you), and after a new release the launcher keeps serving the previous version for one
more session before the background update takes effect. Pinning with `SCALASEMANTIC_VERSION` disables
the background updater — you get exactly that version every launch.

Install the launcher to a **stable path on PATH** (`~/.local/bin/scalasemantic-mcp`) so `.mcp.json`
does not depend on where this repo is cloned — and, unlike the sbt dev launcher under `target/`, it
survives `sbt clean`:

```sh
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/install.sh | sh
# or, from a checkout:  scripts/install.sh
```

`install.sh` also **prefetches** the jar so the first real MCP connect hits a warm cache. If you wire
the launcher up by other means, warm it once yourself with `scalasemantic-mcp --prefetch .` — otherwise
the first connect races the download against the client's connection timeout.

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
