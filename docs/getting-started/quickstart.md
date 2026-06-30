# Quickstart

Shortest path for an sbt project.

## 1. Add the sbt plugin

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "@VERSION@")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

The plugin enables SemanticDB and provides a launcher that downloads the MCP server jar on first use. (`@VERSION@` is filled at doc-site build time; for the raw source check [Maven Central](https://central.sonatype.com/artifact/io.github.mercurievv/sbt-scalasemantic-mcp_2.12_1.0) or [GitHub Releases](https://github.com/MercurieVV/ScalaSemantic/releases/latest).)

## 2. Generate MCP config and steering rules

```sh
sbt mcpClientConfig        # default: Claude Code (.mcp.json)
sbt "mcpClientConfig all"  # generate configs for all supported clients at once
```

This writes the `scala-semantic` server entry into the appropriate client config file and creates `SCALA_SEMANTIC_RULES.md` with steering instructions for agents, then references it from client-specific rules (`CLAUDE.md`, `AGENTS.md`, `.cursorrules`, etc.).

## 3. Compile

```sh
sbt compile
```

ScalaSemantic reads compiler-emitted SemanticDB. Re-run `compile` after source changes the agent needs to see. Restart the MCP session to reload the index.

## 4. Ask a semantic question

Good first queries:
- "Find the exact usages of method `foo`."
- "Which classes extend this trait?"
- "Which given can satisfy `Show[Int]`?"
- "Is there a call path from method `a` to method `c`?"

The server's `initialize` instructions tell the agent to prefer semantic tools for Scala symbol questions and use text search for comments, strings, config files, and broken code.

## Other setups

Use [Integration](integration.md) for non-sbt builds, the auto-download launcher, plain `java -jar`, logging, and manual stdio checks.
