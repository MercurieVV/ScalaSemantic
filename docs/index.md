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

- [Quickstart](getting-started/quickstart.md) — shortest sbt setup path (5 minutes).
- [Integration](getting-started/integration.md) — sbt plugin, launcher script, plain jar, and logging.
- [FAQ](getting-started/faq.md) — SemanticDB, compile freshness, Metals/LSP, and install choices.

### Reference

- [Tool reference](reference/tools.md) — MCP tools, SemanticDB symbol grammar, request shape.
- [Examples](usage/examples.md) — representative MCP calls and responses.

### Understand the trade-offs

- [ScalaSemantic vs grep](explanation/scala-semantic-vs-grep.md) — exact symbols vs text search, token savings, Metals scope.
- [The motivation](articles/it-hurts-to-watch-ai-grep-my-scala.md) — why SemanticDB beats grep for code understanding.

### Project & Development

- [Development](project/development.md) — repository layout, build, test, cross-version compatibility.
- [Design decisions](project/design.md) — implementation approach and extension points.
- [Releasing](project/releasing.md) — Sonatype Central release process.

### Research & Backlog

- [Claude interaction study](research/claude-interaction-study.md) — measured tool usage to guide future work.
- [LLM steering](research/llm-steering-investigation.md) — how to encourage agents to use semantic tools.
- [Plan & tracker](research/plan.md) — roadmap, implemented decisions, and known limitations.
