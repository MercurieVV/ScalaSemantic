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

It is exposed over [MCP](https://modelcontextprotocol.io), so clients such as Claude Code, Codex, and
Gemini CLI can call it as a local tool while you keep using your normal Scala build.

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
Full trade-offs, including where `grep` wins:
[docs/explanation/scala-semantic-vs-grep.md](docs/explanation/scala-semantic-vs-grep.md).

## Quickstart

Your MCP client spawns the server over stdio. Two things are needed (only a **JVM** — no coursier, no sbt):

1. the target project **compiled with SemanticDB** (`semanticdbEnabled := true`);
2. MCP client config that launches the server with that project's root as its argument.

Pick one setup:

### sbt plugin — recommended

Least manual: works on sbt 1 and sbt 2, enables SemanticDB, and generates MCP client config for you;
the jar arrives on first spawn.

```scala
// project/plugins.sbt — replace x.y.z with the latest release (see the badge / releases page)
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "x.y.z")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

Latest version: [![Maven Central](https://img.shields.io/maven-central/v/io.github.mercurievv/sbt-scalasemantic-mcp_2.12_1.0)](https://central.sonatype.com/artifact/io.github.mercurievv/sbt-scalasemantic-mcp_2.12_1.0) · [GitHub releases](https://github.com/MercurieVV/ScalaSemantic/releases/latest)

`sbt mcpClientConfig` writes/merges the client configuration (or you can specify the client as a CLI argument, e.g., `sbt "mcpClientConfig gemini"`); `sbt mcpRun` runs the server for testing. The default client is `claude` (which emits `.mcp.json` for Claude Code). Other supported clients are: `codex` (for Codex `config.toml`), `gemini` (Gemini CLI), `cline`, `roo` (Roo Code), `continue` (Continue YAML), or `generic-json`.

Running `mcpClientConfig` also automatically generates a [SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md) file containing coding rules for the agent, and creates/updates client-specific rules (like `CLAUDE.md`, `AGENTS.md`, or `.cursorrules`) pointing to it.

### Any build tool / OS

- **Auto-download launcher** — `curl -fsSL .../scripts/install.sh | sh` (Windows: `…ps1`), then set
  your MCP client `command` to `~/.local/bin/scalasemantic-mcp`. Fetches + caches the fat jar on first run.
- **Plain `java -jar`** — grab `scalasemantic-mcp.jar` from the
  [latest release](https://github.com/MercurieVV/ScalaSemantic/releases/latest) and run it directly.

**Optional launcher flags** — append to the MCP client `args` after the project root (the launcher
forwards them to the server; equivalent env vars in parentheses):

- `--prefetch` — download + cache the jar, then exit without serving.
- `--log` (`SCALASEMANTIC_LOG`) — log a tool call input to file.
- `--log-output` (`SCALASEMANTIC_LOG_OUTPUT`) — log a tool call output to file.
- 
```json
{
  "mcpServers": {
    "scala-semantic": {
      "command": "~/.local/bin/scalasemantic-mcp",
      "args": ["/abs/path/to/project", "--log", "--log-output"]
    }
  }
}
```

Full setup, generated config, and lifecycle:
**[docs/getting-started/integration.md](docs/getting-started/integration.md)**.

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

There are two analysis paths, with different version coverage:

- **Disk SemanticDB (primary path)** — the server *reads* the `*.semanticdb` files your build emits,
  so it is largely compiler-version-agnostic: it works against any project that compiles cleanly with
  SemanticDB enabled. Cross-version behavior is enforced by tests — the analyzer is exercised against
  golden SemanticDB from **Scala 2.13.\* and 3.\*.\*** (see
  [docs/project/development.md](docs/project/development.md#cross-version-compatibility-test)).
- **Presentation-compiler (live) path** — used for position-local tools on an edited / uncompiled /
  broken in-memory buffer, before a clean compile exists on disk. This embeds Scala 3's own
  presentation compiler, which is **version-locked to Scala 3.8.4** (the only PC build wired in today).
  In principle the Scala 3 compiler can parse older 3.\* source, but no per-version PC switching ships
  yet, and Scala 2.13 is *not* supported on this path (Scala 3's compiler is not a Scala 2 compiler).

So: disk-based analysis covers **2.13.\* + 3.\*.\***; the live broken-buffer path is **Scala 3.8.4
only**. The server itself runs on any JVM (`java` 11+); the target project's Scala version is independent.

## Docs

- **[Documentation site](https://mercurievv.github.io/ScalaSemantic/)** — the rendered, mdoc-checked microsite
- [Quickstart](docs/getting-started/quickstart.md) — shortest sbt setup path
- [Integration](docs/getting-started/integration.md) — register with a client, sbt plugin, other build tools
- [Tool reference](docs/reference/tools.md) — MCP tools, symbol grammar, request shape
- [Examples](docs/usage/examples.md) — sample MCP queries and responses
- [ScalaSemantic vs grep](docs/explanation/scala-semantic-vs-grep.md) — trade-offs and measured context cost
- [Development](docs/project/development.md) — module layout, build & test, cross-version testing
- [Design decisions](docs/project/design.md) — implementation notes and future extension points
- [Releasing](docs/project/releasing.md) — Sonatype Central release process

## License

[MIT](LICENSE)
