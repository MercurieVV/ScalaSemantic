# ScalaSemantic

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mercurievv/scalasemantic-core_3?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.mercurievv)
[![CI](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml/badge.svg)](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Deep semantic analysis of Scala projects, exposed over [MCP](https://modelcontextprotocol.io) so an
AI agent (e.g. Claude Code) can ask precise questions about **symbols, types, implicits, and call
paths**. It reads compiler-emitted **SemanticDB**, so answers reflect what the compiler actually
resolved — not text matching.

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
Full comparison incl. Metals/LSP: [docs/COMPARISON.md](docs/COMPARISON.md).

## Quickstart

The server is spawned by your MCP client over stdio. You need just two things: the **target project
compiled with SemanticDB** (`semanticdbEnabled := true`), and a `.mcp.json` entry that launches the
server with that project's root as its argument. Only a **JVM** is required — no coursier, no sbt.

### Recommended — auto-download launcher (any OS)

Grab the cross-platform launcher script ([`scripts/`](scripts/)) — it downloads the latest fat jar
from GitHub Releases once (cached by version) and runs it. Register it in your client:

```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "/abs/path/to/scalasemantic-mcp.sh",
      "args": ["/abs/path/to/project-to-analyze"]
    }
  }
}
```

Use `scalasemantic-mcp.sh` on Linux/macOS, `scalasemantic-mcp.ps1` on Windows. First launch downloads
the jar; later launches are offline-friendly.

### Plain `java -jar`

Download `scalasemantic-mcp.jar` from the [latest release](https://github.com/MercurieVV/ScalaSemantic/releases/latest),
then point your client at it:

```json
{ "mcpServers": { "scala-semantic": {
  "command": "java",
  "args": ["-jar", "/abs/scalasemantic-mcp.jar", "/abs/path/to/project-to-analyze"] } } }
```

### sbt plugin (auto-writes the `.mcp.json` + enables SemanticDB)

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "0.1.0")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
mcpServerCommand := Seq("java", "-jar", "/abs/scalasemantic-mcp.jar")
```

Then `sbt mcpClientConfig` prints the `.mcp.json` (root = project base dir); `sbt mcpRun` runs it in
the foreground for testing.

Full details (build tools, the generated `.mcp.json`, lifecycle): **[docs/INTEGRATION.md](docs/INTEGRATION.md)**.

## Tools

| Tool | Answers |
|------|---------|
| `find_usages` | references to a symbol, def/ref split, paged |
| `method_signature` | full signature incl. implicit/using parameter lists |
| `class_hierarchy` | parents, linearization, index-wide known subtypes |
| `find_overloads` | all overloads sharing a name and owner |
| `members` | declared vs inherited members (override-aware) |
| `type_at_position` | symbol + type at a 0-based position |
| `resolve_implicits` | `given` definitions that produce a type |
| `trace_implicit_chain` | a given's transitive implicit dependencies |
| `call_path` | shortest call path between two methods |

Results are **lean by default** (locations as `uri:line:col`, signatures as one line, empty fields
omitted) to keep token use low; pass `"detailed": true` to expand, and `find_usages` is paged.

## Docs

- [Integration](docs/INTEGRATION.md) — register with a client, sbt plugin, other build tools
- [Comparison](docs/COMPARISON.md) — capability comparison vs Metals/LSP, with evidence
- [Development](docs/DEVELOPMENT.md) — module layout, build & test
- [Releasing](docs/RELEASING.md) — Sonatype Central release process
- [PLAN.md](PLAN.md) — design decisions & execution tracker

## License

[MIT](LICENSE)
