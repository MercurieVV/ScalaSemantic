# ScalaSemantic

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mercurievv/scalasemantic-core_3?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.mercurievv)
[![CI](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml/badge.svg)](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-site-blue)](https://mercurievv.github.io/ScalaSemantic/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**ScalaSemantic** is an MCP server that helps AI understand Scala code like the compiler does. Instead
of asking agents to grep and guess, it offers precise, project-wide semantic queries — exact symbols,
types, inheritance, usages, implicits, and call paths — over compiler-emitted **SemanticDB**.

Clients such as Claude Code, Codex, and Gemini CLI can spawn it as a local tool. It works with any
Scala version (2.13.* and 3.*.*) as long as the target project compiles with SemanticDB enabled.

📖 **Start here:** [Documentation site](https://mercurievv.github.io/ScalaSemantic/) or
[docs/index.md](docs/index.md) · [Quickstart](docs/getting-started/quickstart.md)

## Quick comparison: SemanticDB vs `grep`

| Need | Tool |
|---|---|
| Exact usages of a method | `find_usages` |
| All classes that extend a trait | `class_hierarchy` |
| Which `given`s satisfy a type | `resolve_implicits` |
| Call path between two methods | `call_path` |
| Comments, TODOs, or broken code | `grep` |

Full trade-offs and measured token savings: [docs/explanation/scala-semantic-vs-grep.md](docs/explanation/scala-semantic-vs-grep.md).

## Install (sbt — recommended)

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "@VERSION@")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

Then:
```sh
sbt mcpClientConfig      # generates MCP config + SCALA_SEMANTIC_RULES.md
sbt compile              # emits SemanticDB
```

Other setups (auto-download launcher, plain `java -jar`): [Integration guide](docs/getting-started/integration.md).

## Tools

All MCP tools return compiler-resolved facts, paged and lean by default. Start with `find_symbol` to
resolve a plain name to a SemanticDB symbol, then use that symbol with more specific queries.

Full reference: [docs/reference/tools.md](docs/reference/tools.md)

| Tool | Purpose |
|---|---|
| `find_symbol` | Resolve a name to a symbol |
| `find_usages` | References to a symbol |
| `class_hierarchy` | Parents and known subtypes |
| `method_signature` | Full signature with type/implicit params |
| `resolve_implicits` | Given definitions for a type |
| `call_path` | Shortest call route between methods |

See [Examples](docs/usage/examples.md) for sample queries and responses.

## Documentation

- [**Quickstart**](docs/getting-started/quickstart.md) — shortest sbt path (5 minutes)
- [**Integration**](docs/getting-started/integration.md) — sbt plugin, launcher, plain jar, logging
- [**Tool reference**](docs/reference/tools.md) — MCP tools and SemanticDB symbol grammar
- [**Examples**](docs/usage/examples.md) — sample MCP calls and responses
- [**SemanticDB vs grep**](docs/explanation/scala-semantic-vs-grep.md) — trade-offs and context cost
- [**FAQ**](docs/getting-started/faq.md) — SemanticDB, compile freshness, Metals, install choices
- [**Development**](docs/project/development.md) — modules, build, test, cross-version
- [**Design notes**](docs/project/design.md) — implementation and extension points
- [**Releasing**](docs/project/releasing.md) — Sonatype Central process

For the full map, see [docs/index.md](docs/index.md).

## License

[MIT](LICENSE)
