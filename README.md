# ScalaSemantic

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mercurievv/scalasemantic-core_3?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.mercurievv)
[![CI](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml/badge.svg)](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-site-blue)](https://mercurievv.github.io/ScalaSemantic/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**ScalaSemantic** helps AI understand your Scala code like the compiler does. Instead of asking an
agent to guess from text search (grep), you let it query compiler-emitted **SemanticDB** for exact symbols,
types, implicits, inheritance, usages, and call paths.

For a Scala developer, that means an agent can answer questions like:

- where a method is really used, without false matches from `grep`;
- which classes extend a trait or override a member;
- which `given` can satisfy a type;
- how one method can call another across the project.

It is exposed over [MCP](https://modelcontextprotocol.io), so clients such as Claude Code can call it
as a local tool while you keep using your normal Scala build.

📖 **Documentation site: <https://mercurievv.github.io/ScalaSemantic/>** (mdoc-checked, so its code
samples are executed at build time).

## Why, vs `grep`

`grep` matches characters; ScalaSemantic understands the compiled program.

| You want to know… | `grep` | ScalaSemantic |
|---|---|---|
| Who extends `Animal`? | every line containing "Animal" | exact subtypes — `class_hierarchy` |
| All usages of method `foo` | every "foo", unrelated included | exact symbol references — `find_usages` |
| Which `given`s produce `Show[Int]`? | — not possible | `resolve_implicits` |
| Call path from `a` to `c`? | — not possible | `call_path` |

Every capability is backed by a test that runs against this repo's own SemanticDB →
[`AnalyzerSuite`](analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/AnalyzerSuite.scala).
Full trade-offs, including where `grep` wins: [docs/COMPARISON.md](docs/COMPARISON.md).

## Quickstart

Your MCP client spawns the server over stdio. Two things are needed (only a **JVM** — no coursier, no sbt):

1. the target project **compiled with SemanticDB** (`semanticdbEnabled := true`);
2. a `.mcp.json` entry that launches the server with that project's root as its argument.

Pick one setup:

### sbt plugin — recommended

Least manual: enables SemanticDB and generates the `.mcp.json` for you; the jar arrives on first spawn.

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "0.2.1")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

`sbt mcpClientConfig` prints the entry; `sbt mcpRun` runs the server for testing.

### Any build tool / OS

- **Auto-download launcher** — `curl -fsSL .../scripts/install.sh | sh` (Windows: `…ps1`), then set
  `.mcp.json` `command` to `~/.local/bin/scalasemantic-mcp`. Fetches + caches the fat jar on first run.
- **Plain `java -jar`** — grab `scalasemantic-mcp.jar` from the
  [latest release](https://github.com/MercurieVV/ScalaSemantic/releases/latest) and run it directly.

Full setup, generated config, and lifecycle: **[docs/INTEGRATION.md](docs/INTEGRATION.md)**.

## Tools

| Tool | Answers |
|------|---------|
| `find_symbol` | resolve a plain/partial name to SemanticDB symbol strings — **start here** |
| `find_usages` | references to a symbol, def/ref split, paged |
| `method_signature` | full signature incl. implicit/using parameter lists |
| `class_hierarchy` | parents, linearization, index-wide known subtypes |
| `find_overloads` | all overloads sharing a name and owner |
| `members` | declared vs inherited members (override-aware) |
| `type_at_position` | symbol + type at a 0-based position |
| `resolve_implicits` | `given` definitions that produce a type |
| `trace_implicit_chain` | a given's transitive implicit dependencies |
| `call_path` | shortest call path between two methods |

Every tool takes a SemanticDB symbol string; call `find_symbol` first to get one from a plain name.
On `initialize` the server also sends `instructions` telling the agent to **prefer these tools over
`grep`** for Scala code questions. Results are **lean by default** (locations as `uri:line:col`,
signatures one line, empty fields omitted); pass `"detailed": true` to expand, and `find_usages` is
paged.

## Supported Scala versions

The server *reads* SemanticDB, so it is compiler-version-agnostic: it works against any project that
emits SemanticDB. Cross-version behavior is enforced by tests — the analyzer is exercised against
golden SemanticDB from **Scala 2.13 and 3.x** (see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#cross-version-compatibility-test)).
The server itself runs on any JVM (`java` 11+); the target project's Scala version is independent.

## Docs

- **[Documentation site](https://mercurievv.github.io/ScalaSemantic/)** — the rendered, mdoc-checked microsite
- [Integration](docs/INTEGRATION.md) — register with a client, sbt plugin, other build tools
- [Comparison](docs/COMPARISON.md) — vs `grep` (pros & cons), plus a note on Metals/LSP
- [Development](docs/DEVELOPMENT.md) — module layout, build & test, cross-version testing
- [Design decisions](docs/DESIGN.md) — why upickle, extensibility (external-jar tools), docs tooling
- [Releasing](docs/RELEASING.md) — Sonatype Central release process

## License

[MIT](LICENSE)
