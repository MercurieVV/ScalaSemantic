# Integration

ScalaSemantic is an MCP **stdio** server — the MCP client spawns it as a process and owns its lifecycle. Integrating means two things: make the target project emit SemanticDB, and register a launch command for that project root.

The server speaks newline-delimited JSON-RPC 2.0 on **stdout**. Diagnostic logging is **off by default** and, when enabled, goes to a file — never to stdout. See [Logging](#logging).

## Prerequisite: SemanticDB on the target project

The server reads SemanticDB; it does not generate it. The project must be compiled with SemanticDB enabled:

**sbt** (also done automatically by the auto-download script's `setup`):

```scala
// build.sbt
semanticdbEnabled := true
```

**Mill** — Mill's `def semanticDbEnabled = true` only feeds the on-demand `semanticDbData` target;
a plain `mill __.compile` emits **no** `*.semanticdb`, so that flag alone leaves ScalaSemantic with an
empty index. Instead make the normal compile emit it via the compiler flag, in each `ScalaModule`
(`-sourceroot` must be the **build root**, not the module dir, so multi-module source paths stay unique):

```scala
// build.mill / build.sc
def scalacOptions = super.scalacOptions() ++
  Seq("-Xsemanticdb", "-sourceroot", build.moduleDir.toString) // build.sc: build.millSourcePath
```

(Alternatively, keep `def semanticDbEnabled = true` and run `mill __.semanticDbData` instead of
`mill __.compile` — the Mill-native target that materializes the files under `out/`.)

**Gradle** — no native flag; pass the compiler option directly via the Scala plugin's compile task. Scala 3:

```groovy
tasks.withType(ScalaCompile) {
  scalaCompileOptions.additionalParameters = ["-Ysemanticdb", "-sourceroot", projectDir.toString()]
}
```

Scala 2.13 needs the `semanticdb-scalac` compiler plugin jar instead of a native flag:

```groovy
scalaCompilerPlugins "org.scalameta:semanticdb-scalac_2.13.16:4.13.9"
tasks.withType(ScalaCompile) {
  scalaCompileOptions.additionalParameters = ["-Yrangepos", "-P:semanticdb:sourceroot:${projectDir}"]
}
```

**Plain `scalac`** — Scala 3:

```sh
scalac -Ysemanticdb -sourceroot . <sources...>
```

Scala 2.13 (resolve the plugin jar with coursier first):

```sh
scalac -Xplugin:/path/to/semanticdb-scalac_2.13.16-4.13.9.jar -Yrangepos -P:semanticdb:sourceroot:. <sources...>
```

Whatever the build tool, the only machine requirement to *run* the server is a **JVM** (`java` on PATH).

Each release publishes both a self-contained fat jar attached to the [GitHub Release](https://github.com/MercurieVV/ScalaSemantic/releases) and the same server as regular Maven Central artifacts (`io.github.mercurievv::scalasemantic-mcp` and friends). Options A and C run the fat jar; option B resolves the Maven Central artifact directly via scala-cli/coursier.

## Three ways to launch

| | A — Scala CLI remote script | B — auto-download script | C — plain `java -jar` |
|---|---|---|---|
| Get the jar | scala-cli/coursier resolve + cache the published artifact | script downloads + caches | you download once |
| Write client config | `scala-cli ... setup` | by hand | by hand |
| Enable SemanticDB | script creates sbt config if missing | you add one line | you add one line |
| Stays up to date | yes (`latest.release`) | yes | manual |
| Works with | any build tool with Scala CLI installed | any build tool | any build tool |

### Option A — Scala CLI remote script

If `scala-cli` is already installed, one command can configure a project without installing a
separate launcher:

```sh
scala-cli https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.scala -- setup --client claude
```

Use `--client codex`, `gemini`, `cline`, `roo`, `continue`, `antigravity`, `generic-json`, or
`all`. The script writes/merges the MCP client config, creates `SCALA_SEMANTIC_RULES.md`, creates a
small `scala-semantic.sbt` file with `semanticdbEnabled := true` when it finds an sbt project that
does not already configure SemanticDB, and registers this command:

```sh
scala-cli run --dependency "io.github.mercurievv::scalasemantic-mcp:latest.release" \
  --main-class com.github.mercurievv.scalasemantic.mcpServer \
  -- /abs/path/to/project ~/.local/bin/scala-semantic-classpath.txt
```

The MCP client runs that command over stdio. Note this does **not** invoke the setup script itself —
the generated config skips it entirely so that every server launch (which happens far more often than
`setup`, e.g. on every editor restart) is just a coursier-cached jar load: no network fetch, no
recompile. The dependency version is `latest.release`, a coursier magic version that always
re-resolves to the newest published release.

To pin a specific version instead of `latest.release`, edit `ServerDependency` in a local copy of the
script and re-run `setup`.

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

Next, use the [Tool reference](../reference/tools.md) for the full tool list and [Examples](../usage/examples.md) for worked requests.
