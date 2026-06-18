# Integration

An MCP **stdio** server is spawned by the MCP client (e.g. Claude Code), which owns its lifecycle —
you don't run it as a daemon. Integrating means two things: make the project emit SemanticDB, and
register a launch command scoped to that project's root. Because the unit is a plain process, the
same approach works from any build tool.

The server speaks newline-delimited JSON-RPC 2.0 on **stdout** and logs to **stderr**. Point it at a
directory that contains emitted `*.semanticdb` files (the target project must be compiled with
SemanticDB enabled).

## Standalone launcher (any build tool / bare shell)

```sh
sbt "mcp/mcpLauncher"        # writes target/.../scalasemantic-mcp (clean-stdout java launcher)
sbt "mcp/mcpClientConfig"    # prints the ready-to-paste .mcp.json entry for this repo
```

Register the printed entry with your MCP client; it will spawn `scalasemantic-mcp <root>` on demand.
For Mill/Gradle/CLI: enable SemanticDB in that tool, then point the client at the same launcher (or
`java -jar`) with the project root as the argument.

> A bare `sbt runMain` writes its own build logs to stdout and corrupts the JSON-RPC stream — always
> launch the compiled app (the generated launcher, or a packaged jar) so stdout carries only protocol
> messages.

## sbt plugin (convenience)

`io.github.mercurievv:sbt-scalasemantic-mcp` (built for sbt 2; cross-publish for sbt 1 with `^`). In a
host build:

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "0.1.0")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
mcpServerCommand := Seq("/abs/path/to/scalasemantic-mcp") // or Seq("java","-jar","scalasemantic-mcp.jar")
```

Then `sbt mcpClientConfig` prints the `.mcp.json` entry (SemanticDB root = the project's base dir) and
`sbt mcpRun` runs the server in the foreground for manual testing. The plugin only enables SemanticDB
and shells out to the launch command — it never links against the Scala 3.8.4 server, which is why it
is sbt-1/2 and build-tool portable.

## Manual stdio check

```sh
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"class_hierarchy","arguments":{"symbol":"com/github/mercurievv/scalasemantic/fixtures/Animal#"}}}' \
 | ./target/.../scalasemantic-mcp .
```

Expect three JSON-RPC responses on stdout (stderr carries the startup log).
