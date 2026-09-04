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

The setup script also creates `scala-semantic.sbt` with small `scalaSemanticWriteClasspath` and
`scalaSemanticWriteModules` tasks. They read build modules and `Compile / fullClasspath`, write
`.scala-semantic/classpath-sbt.json` plus `.scala-semantic/modules-sbt.json`, and should be run
after dependency or module configuration changes:

```sh
sbt scalaSemanticWriteClasspath
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

For live-buffer typechecking, write `.scala-semantic/classpath-mill.json` and
`.scala-semantic/modules-mill.json` from the build. This repo ships compact Mill examples as root
tasks `scalaSemanticWriteClasspath` and `scalaSemanticWriteModules`; run the classpath task after
dependency or module configuration changes:

```sh
./mill scalaSemanticWriteClasspath
```

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

**Scala CLI** — use the directive/flag, not raw scalac options, and compile the **test scope too**:

```sh
scala-cli compile . --test --semanticdb --semanticdb-sourceroot . --semanticdb-targetroot .semanticdb
```

Without `--test`, `scala-cli compile .` builds the main scope only, so every `*.test.scala` is
missing from the index — see [Coverage](#coverage-what-the-index-does-not-see) below.

Whatever the build tool, the only machine requirement to *run* the server is a **JVM** (`java` on PATH).

## Coverage: what the index does not see

The server answers from the `*.semanticdb` the compiler emitted. A source file that was never
compiled with SemanticDB enabled — a test scope left out of the build, a module not compiled yet, a
standalone script outside the build — is simply not in the index, and a query about a symbol defined
there returns `count: 0`. That is the same answer as "this symbol does not exist", which is the
answer a *"nothing uses this, delete it"* decision rests on.

So the server measures it: `get_workspace_root`, `set_workspace_root` and `refresh_workspace` return

```json
"coverage": { "sources": 82, "indexed": 70, "unindexed": ["src/AgentInventory.test.scala", "…"] }
```

and any tool result that is empty **while coverage is partial** carries a `coverageHint` saying so.
Treat that hint as "may not be indexed", not as "does not exist". Files legitimately outside the
build will always be listed as unindexed; the number to watch is a scope you expected to be there.

## Freshness: recompiles are picked up automatically

Every tool call re-checks the project's `*.semanticdb` files on disk (a walk, without parsing) and
rebuilds the index when they changed. A recompile therefore needs **no** `refresh_workspace` call —
that tool remains available to force a rebuild the on-disk check cannot see, or to rebuild a root
other than the active one.


Each release publishes both a self-contained fat jar attached to the [GitHub Release](https://github.com/MercurieVV/ScalaSemantic/releases) and the same server as regular Maven Central artifacts (`io.github.mercurievv::scalasemantic-mcp` and friends). Options A and C run the fat jar; option B resolves the Maven Central artifact directly via scala-cli/coursier.

## Three ways to launch

One script serves both installs; see
[ADR-0004](../adr/0004-single-launcher-script-and-user-scope-install.md).

| | User scope (default) | Project scope (`--project`) | Plain `java -jar` |
|---|---|---|---|
| Command | `curl … \| sh` | `curl … \| sh -s -- --project` | you download the jar |
| Launcher | `~/.local/bin/scalasemantic-mcp` | `./scalasemantic-mcp.sh`, committable | none |
| Jar | `~/.local/share/scalasemantic-mcp/`, shared | same shared directory | you manage it |
| Client config | per-user, every project at once | per-project, committable | by hand |
| Enables SemanticDB / rules / guard hook | no | yes | no |
| `command` written | absolute | relative (ADR-0002) | absolute |
| Stays up to date | yes | yes | manual |

Pick **user** unless you want the launcher version pinned and the config committed with the repo, or
you use Cline or Roo (which have no user-level config location this installer can write). Pick
**project** for a repo that has not been set up for SemanticDB yet — only that mode configures the
build, writes `SCALA_SEMANTIC_RULES.md` and installs the Claude guard hook.

### Option B — auto-download launcher (default)

```sh
# user scope
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh

# project scope, from the project root
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh -s -- --project
```

Piped to `sh` the script cannot resolve its own path, so it installs a copy of itself to the target
location and re-execs it — one URL, both flows. It then downloads and caches the fat jar from GitHub
Releases. Pin a version with `SCALASEMANTIC_VERSION=vX.Y.Z`; move the jar directory with
`SCALASEMANTIC_HOME`; move the user-scope launcher with `BIN_DIR`.

To (re-)register later without reinstalling:

```sh
scalasemantic-mcp setup --scope user                     # or: --scope project, from the project
scalasemantic-mcp setup --scope project --client claude  # one client only
```

Config file written per client and scope:

| client | project scope | user scope |
|---|---|---|
| claude | `.mcp.json` | `~/.claude.json` |
| codex | `.codex/config.toml` | `~/.codex/config.toml` |
| gemini | `.gemini/settings.json` | `~/.gemini/settings.json` |
| continue | `.continue/config.yaml` | `~/.continue/config.yaml` |
| antigravity | `.agents/mcp_config.json` | `~/.gemini/config/mcp_config.json` |
| cline | `.cline/mcp.json` | — skipped, no known location |
| roo | `.roo/mcp.json` | — skipped, no known location |

> **Codex caveat.** `codex` loads config from `~/.codex/config.toml`, overridable only via
> `$CODEX_HOME`; it does not discover a project-local `.codex/config.toml`. The project-scope file is
> written but not read — use the user scope for Codex, or point `CODEX_HOME` at the project's
> `.codex` directory.

Or register manually — run from inside the project so the client's own cwd = project root:

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "scalasemantic-mcp",
      "args": ["serve", "."]
    }
  }
}
```

Since `command` may be a single machine-wide binary (not something living inside the project), the
server validates that `.` (the default root) actually looks like a Scala project before trusting it
— see [ADR-0003](../adr/0003-global-install-default-and-root-discovery.md). If your build tool uses
none of the recognized markers (`build.mill`, `build.sbt`, `pom.xml`, `build.gradle[.kts]`,
`project/build.properties`, `project.scala`), either add one or set
`SCALASEMANTIC_SKIP_ROOT_CHECK=1`.

### Launching from a directory that is not a Scala project

A user-scope registration means MCP clients start the server in your Python, Node and docs repos
too. Nothing breaks: the server connects, `tools/list` returns the full set, and each tool call
answers `could not detect a Scala project root at or above …` instead of a confident empty result.
Fix it in band with `set_workspace_root`, or launch with an explicit root
(`scalasemantic-mcp serve /path/to/project`). This replaces the earlier behaviour of exiting at
startup — see [ADR-0004](../adr/0004-single-launcher-script-and-user-scope-install.md).

### Worktrees and cwd changes

Generated configs use `.` as the server root so a newly spawned MCP server indexes the directory it
was launched from, not the directory where `setup` originally ran. The server also discovers
`.scala-semantic/classpath-*.json` from that active root, follows `.scala-semantic/modules-*.json`
to child source and output directories, and falls back to visible submodule scanning when no direct
or module-guided metadata exists. Some stdio MCP clients keep the same server process alive when
the agent later changes cwd or enters a git worktree, and do not reliably send root-change
notifications. After such a cwd change, call `set_workspace_root` with the new absolute path before
other ScalaSemantic tools; use `get_workspace_root` to confirm the current state and discovered
classpath metadata.

### Option C — plain `java -jar`

Download `scalasemantic-mcp.jar` from the [latest release](https://github.com/MercurieVV/ScalaSemantic/releases/latest):

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "java",
      "args": ["-jar", "/abs/path/to/scalasemantic-mcp.jar", "."]
    }
  }
}
```

> Do not use `runMain` (sbt or Mill) — it writes build logs to stdout and corrupts the JSON-RPC stream. To build the jar locally: `./mill mcp.assembly`.

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

Next, use the [Tool reference](../reference/tools.md) for the full tool list and <a href="../../usage/tool-examples">Tool Examples</a> for worked requests.

## Classpath Metadata & Migration from Flat Classpath

In previous versions, a single flat, colon-separated classpath file was passed to the server. The
server now discovers module-aware JSON classpath metadata by default from
`.scala-semantic/classpath-sbt.json`, `.scala-semantic/classpath-mill.json`, or
`.scala-semantic/classpath-scala-cli.json` under the active workspace root. It also follows
`.scala-semantic/modules-sbt.json`, `.scala-semantic/modules-mill.json`,
`.scala-semantic/modules-scala-cli.json`, or `.scala-semantic/modules.json` to discover child module
metadata in source and output directories. If no direct or module-guided metadata exists, it scans
non-hidden subdirectories for submodule metadata, including
`<submoduleOutDir>/.scala-semantic/classpath.json` in visible build output directories. You can still
pass an explicit classpath file as the optional second `serve` argument, or set
`SCALASEMANTIC_CLASSPATH`, to override discovery.

### Automatic Migration
The setup command (via option A/B) automatically detects the build tool and generates the correct
`.scala-semantic/classpath-<tool>.json` file, plus `.scala-semantic/modules-<tool>.json` when the
build integration can expose module topology. It also configures the build tool (e.g., creating
`scala-semantic.sbt` for sbt) to maintain metadata freshness automatically. Generated MCP client
configs no longer pass this file path; the server finds it from the current workspace root.

### Troubleshooting Classpath Freshness
If you import your project and live-buffer typechecking is not working (e.g., you see unresolved types or imports for new code):
1. **For sbt projects:** Starting sbt or reloading the build will automatically trigger classpath generation via the `onLoad` hook. You can also run the task manually:
   ```sh
   sbt scalaSemanticWriteClasspath
   ```
2. **For Mill projects:** Compiling the project (`mill __.compile` or via BSP/IDE build import) automatically updates each module's classpath metadata. You can also run the command manually:
   ```sh
   ./mill scalaSemanticWriteClasspath
   ```
3. **For Scala CLI projects:** Re-run setup to regenerate the classpath metadata:
   ```sh
   scalasemantic-mcp setup --scope project --client <your-client>
   ```
