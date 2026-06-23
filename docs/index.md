# ScalaSemantic

Deep semantic analysis of Scala projects over [MCP](https://modelcontextprotocol.io). It reads
compiler-emitted **SemanticDB**, so answers reflect what the compiler resolved — not text matching.

ScalaSemantic is for AI coding agents working on Scala. Instead of asking the agent to grep source
text and guess which matches are real, it gives the agent compiler-resolved queries for symbols,
types, usages, inheritance, implicits, members, and call paths.

## Start here

- **Install it fast:** [Quickstart](getting-started/quickstart.md) is the shortest sbt path.
- **Wire a custom setup:** [Integration](getting-started/integration.md) covers the sbt plugin,
  auto-download launcher, plain `java -jar`, logging, and manual stdio checks.
- **See the tool surface:** [Tool reference](reference/tools.md) lists every MCP tool and the symbol
  format they use.
- **Try the tools:** [Examples](usage/examples.md) shows representative MCP calls and compact responses.
- **Decide when to use it:** [ScalaSemantic vs grep](explanation/scala-semantic-vs-grep.md) explains where semantic queries
  win, where text search still wins, and the measured token/context cost.
- **Answer common setup questions:** [FAQ](getting-started/faq.md) covers SemanticDB, compile freshness, Metals/LSP,
  and install-option choice.

## Documentation map

### Get started

- [Quickstart](getting-started/quickstart.md) — shortest sbt setup path.
- [Integration](getting-started/integration.md) — register the server with an MCP client.
- [FAQ](getting-started/faq.md) — MCP, AI-agent, and SemanticDB basics for Scala developers.

### Reference and usage

- [Tool reference](reference/tools.md) — MCP tools, SemanticDB symbol grammar, and request shape.
- [Examples](usage/examples.md) — sample MCP queries, responses, and grep comparisons.

### Explanation

- [ScalaSemantic vs grep](explanation/scala-semantic-vs-grep.md) — trade-offs, measured context cost, and Metals/LSP scope.

### Project

- [Development](project/development.md) — modules, build, cross-version testing, and this site.
- [Design decisions](project/design.md) — implementation notes and future extension points.
- [Releasing](project/releasing.md) — Sonatype Central release process.
- [Release notes](project/release-notes.md) — user-facing changes per version.

### Research

- [Claude interaction study](research/claude-interaction-study.md) — measured agent behavior used to rank
  future tool work.
- [Plan & tracker](research/plan.md) — implementation history, backlog, and known decisions.
