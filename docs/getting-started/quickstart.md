# Quickstart

Use this path when the target project is an sbt build and you want the least manual setup.

## 1. Add the sbt plugin

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "@VERSION@")

// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

The plugin enables SemanticDB and provides a launcher that downloads the MCP server jar on first use.
If you are reading the raw source instead of the rendered site, replace `@VERSION@` with the latest
release from [Maven Central](https://central.sonatype.com/artifact/io.github.mercurievv/sbt-scalasemantic-mcp_2.12_1.0)
or [GitHub Releases](https://github.com/MercurieVV/ScalaSemantic/releases/latest).

## 2. Generate the MCP client config & rules
 
 ```sh
 sbt mcpClientConfig
 ```
 
 This writes/merges the `scala-semantic` server configuration into your project's local config file (e.g., `.mcp.json`). You can also specify the client format directly as a command-line argument (or pass `all` to generate configurations for all supported clients in one go):
 
 ```sh
 sbt "mcpClientConfig gemini"
 # or
 sbt "mcpClientConfig all"
 ```
 
 Running this task also automatically:
 1. Creates a `SCALA_SEMANTIC_RULES.md` file in your project root containing semantic steering guidelines for coding agents.
 2. Creates/updates client-specific rules (such as `CLAUDE.md`, `AGENTS.md`, or `.cursorrules`) to point to it, ensuring AI agents prefer ScalaSemantic tools over text-based `grep`.

## 3. Compile the project

```sh
sbt compile
```

ScalaSemantic reads compiler-emitted SemanticDB. Re-run `compile` after source changes that the agent
needs to see. If the MCP session is already running, restart it so the server reloads the index.

## 4. Ask a semantic question

Good first checks:

- "Find the exact usages of method `foo`."
- "Which classes extend this trait?"
- "Which given can satisfy `Show[Int]`?"
- "Is there a call path from method `a` to method `c`?"

The server's initialization instructions tell the agent to prefer semantic tools for Scala symbol
questions and use text search for comments, strings, config, and broken code.

## Other setups

Use [Integration](integration.md) for non-sbt builds, the auto-download launcher, plain `java -jar`,
logging, and manual stdio checks.
