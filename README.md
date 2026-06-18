# ScalaSemanticMCP

An MCP server that performs deep semantic analysis of Scala projects from compiler-emitted
**SemanticDB**, exposing relationship and implicit-resolution queries that go beyond cursor-based
LSP tooling. See [docs/COMPARISON.md](docs/COMPARISON.md) for the capability comparison vs Metals.

## How it works

Three sbt modules, one per architectural layer:

```
mcp        stdio JSON-RPC server + entrypoint   (com.github.mercurievv.scalasemantic.mcp)
  └─ analysis   query engine + result models    (…​.analysis, …​.model)
       └─ core    load + index SemanticDB        (…​.semanticdb)
```

`core` knows nothing about JSON or MCP; `analysis` adds upickle result models; `mcp` is the only
module that speaks the protocol. The analyzer reads every `*.semanticdb` file under a project root
and answers queries against the whole symbol/occurrence index. Each module emits its own SemanticDB
(`semanticdbEnabled := true`), so all 33 tests run against this codebase itself.

## Tools

| Tool | Purpose |
|------|---------|
| `find_usages` | references to a symbol, def/ref split, paged |
| `method_signature` | full signature incl. implicit/using parameter lists |
| `class_hierarchy` | parents, linearization, index-wide known subtypes |
| `find_overloads` | all overloads sharing a name and owner |
| `members` | declared vs inherited members (override-aware) |
| `type_at_position` | symbol + type at a 0-based position |
| `resolve_implicits` | given definitions that produce a type |
| `trace_implicit_chain` | a given's transitive implicit dependencies |
| `call_path` | shortest call path between two methods |

**Token discipline:** results are lean by default (locations as `uri:line:col`, signatures as one
line, related symbols as display names, empty fields omitted). Pass `"detailed": true` to expand
structured data; `find_usages` is paged via `limit`/`offset`.

## Build & test

```
sbt compile     # also (re)emits this project's SemanticDB
sbt test        # 33 tests, dogfooded on this project
sbt prePush     # scalafmt + scalafix + full test suite (run before pushing)
```

## Running the server

The server speaks newline-delimited JSON-RPC 2.0 on **stdout** and logs to **stderr**. Point it at
a directory that contains emitted `*.semanticdb` files (the target project must be compiled with
SemanticDB enabled):

```
sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <semanticdbRoot>"   # root defaults to "."
```

> Note: a bare `sbt runMain` is fine for development but writes its own build logs to stdout, which
> corrupts the JSON-RPC stream. For integration as an MCP server (e.g. in Claude Code), launch the
> compiled application directly so stdout carries only protocol messages — package a runnable jar
> from the `mcp` module (e.g. add `sbt-assembly`) and invoke `java -jar scalasemantic-mcp.jar
> <root>`, then register that command as the MCP server.

## Integrating with a build tool

An MCP **stdio** server is spawned by the MCP client (e.g. Claude Code), which owns its lifecycle —
you don't run it as a daemon. So integrating means two things: make the project emit SemanticDB, and
register a launch command scoped to that project's root. Because the unit is a plain process, the
same approach works from any build tool.

### Standalone launcher (any build tool / bare shell)

```
sbt "mcp/mcpLauncher"        # writes target/.../scalasemantic-mcp (clean-stdout java launcher)
sbt "mcp/mcpClientConfig"    # prints the ready-to-paste .mcp.json entry for this repo
```

Register the printed entry with your MCP client; it will spawn `scalasemantic-mcp <root>` on demand.
Equivalent for Mill/Gradle/CLI: enable SemanticDB in that tool, then point the client at the same
launcher (or `java -jar`) with the project root as the argument.

### sbt plugin (convenience)

The `sbt-plugin` module publishes `com.github.mercurievv:sbt-scalasemantic-mcp` (built for sbt 2 here;
cross-publish for sbt 1 with `^`). In a host build:

```scala
// project/plugins.sbt
addSbtPlugin("com.github.mercurievv" % "sbt-scalasemantic-mcp" % "0.1.0-SNAPSHOT")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
mcpServerCommand := Seq("/abs/path/to/scalasemantic-mcp") // or Seq("java","-jar","scalasemantic-mcp.jar")
```

Then `sbt mcpClientConfig` prints the `.mcp.json` entry (SemanticDB root = the project's base dir) and
`sbt mcpRun` runs the server in the foreground for manual testing. The plugin only enables SemanticDB
and shells out to the launch command — it never links against the Scala 3.8.4 server, which is why it
is sbt-1/2 and build-tool portable.

### Quick manual check (dev loop, stderr discarded)

```
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"class_hierarchy","arguments":{"symbol":"com/github/mercurievv/scalasemantic/fixtures/Animal#"}}}'
```

Feed those lines to the running server's stdin; expect three JSON-RPC responses on stdout.

## Project status

Phases 0–4 complete; see [PLAN.md](PLAN.md) for the execution tracker and design decisions.
