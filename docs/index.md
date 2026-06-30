# ScalaSemantic — Documentation

ScalaSemantic gives AI coding agents compiler-resolved Scala facts over MCP.
It reads compiler-emitted **SemanticDB**, so answers reflect what the compiler resolved — not text matching.

## Get started

- [Quickstart](getting-started/quickstart.md) — sbt setup in 5 minutes
- [Integration](getting-started/integration.md) — sbt plugin, launcher, plain jar, logging
- [FAQ](getting-started/faq.md) — compile freshness, Metals/LSP, install choices

## Reference

- [Tool reference](reference/tools.md) — all MCP tools and SemanticDB symbol grammar
- [Examples](usage/examples.md) — representative MCP calls and responses

## Understanding the trade-offs

- [ScalaSemantic vs grep](explanation/scala-semantic-vs-grep.md) — where each wins; measured token savings
- [The motivation](articles/it-hurts-to-watch-ai-grep-my-scala.md) — why SemanticDB matters

## Project & Development

- [Development](project/development.md) — repository layout, build, test, cross-version
- [Design decisions](project/design.md) — implementation approach and extension points
- [Releasing](project/releasing.md) — Sonatype Central release process

## Research & Backlog

- [Claude interaction study](research/claude-interaction-study.md) — measured tool usage, tool recommendations
- [LLM steering](research/llm-steering-investigation.md) — steering agents toward semantic tools
- [Plan & tracker](research/plan.md) — roadmap, decisions, known limitations
