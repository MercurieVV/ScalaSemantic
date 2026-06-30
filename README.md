# ScalaSemantic

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mercurievv/scalasemantic-core_3?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.mercurievv)
[![CI](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml/badge.svg)](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-site-blue)](https://mercurievv.github.io/ScalaSemantic/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**ScalaSemantic** is an MCP server that gives AI coding agents compiler-resolved Scala facts — exact symbols, types, inheritance, usages, implicits, and call paths — over compiler-emitted **SemanticDB**. Instead of grepping source text, agents query what the compiler already knows.

Works with Scala 2.13.* and 3.*.*, any sbt/Mill/Gradle project, and any MCP-compatible agent (Claude Code, Codex, Gemini CLI, Cline, Roo Code, Continue…).

## Quick install (sbt)

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "@VERSION@")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

```sh
sbt mcpClientConfig   # writes MCP config + SCALA_SEMANTIC_RULES.md
sbt compile           # emits SemanticDB
```

Other setups: [Integration guide](docs/getting-started/integration.md).

## Tools

| Tool | Purpose |
|---|---|
| `find_symbol` | Resolve a name to a SemanticDB symbol |
| `find_usages` | Exact references to a symbol (paged) |
| `class_hierarchy` | Parents and known subtypes |
| `method_signature` | Full signature with type/implicit params |
| `members` | Declared and inherited members |
| `resolve_implicits` | Given definitions for a type |
| `call_path` | Shortest call route between two methods |
| `type_at_position` | Symbol and type at a source position |
| `trace_implicit_chain` | Transitive given dependencies |
| `find_overloads` | All overloads sharing a name and owner |

Full reference: [docs/reference/tools.md](docs/reference/tools.md)

## SemanticDB vs `grep`

| Question | Right tool |
|---|---|
| Exact callers of a method | `find_usages` |
| All subtypes of a trait | `class_hierarchy` |
| Which `given` satisfies a type | `resolve_implicits` |
| Call path from method `a` to `c` | `call_path` |
| Comments, TODOs, config files | `grep` |
| Code that hasn't compiled yet | `grep` |

Measured: semantic tools use ~90% fewer tokens than grep for symbol questions.
Details: [SemanticDB vs grep](docs/explanation/scala-semantic-vs-grep.md).

## Documentation

- [**Quickstart**](docs/getting-started/quickstart.md) — sbt path, 5 minutes
- [**Integration**](docs/getting-started/integration.md) — sbt plugin, launcher, plain jar, logging
- [**Tool reference**](docs/reference/tools.md) — all MCP tools and SemanticDB symbol grammar
- [**Examples**](docs/usage/examples.md) — sample MCP calls and responses
- [**SemanticDB vs grep**](docs/explanation/scala-semantic-vs-grep.md) — trade-offs and token savings
- [**FAQ**](docs/getting-started/faq.md) — compile freshness, Metals, install choices
- [**Development**](docs/project/development.md) — modules, build, test, cross-version
- [**Releasing**](docs/project/releasing.md) — Sonatype Central release process

Full documentation map: [docs/index.md](docs/index.md)

## License

[MIT](LICENSE)
