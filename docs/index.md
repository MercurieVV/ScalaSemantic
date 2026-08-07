# ScalaSemantic — Documentation

ScalaSemantic gives AI coding agents compiler-resolved Scala facts over MCP.
It reads compiler-emitted **SemanticDB**, so answers reflect what the compiler resolved — not text matching.

## Get started

- [Quickstart](getting-started/quickstart.md) — sbt setup in 5 minutes
- [Integration](getting-started/integration.md) — sbt plugin, launcher, plain jar, logging
- [FAQ](getting-started/faq.md) — compile freshness, Metals/LSP, install choices

## Reference

- [Tool reference](reference/tools.md) — all MCP tools and SemanticDB symbol grammar
- <a href="usage/tool-examples">Tool Examples</a> — real tool calls and responses, executed at docs build time

## Understanding the trade-offs

- [ScalaSemantic vs grep](explanation/scala-semantic-vs-grep.md) — where each wins; measured token savings
- [The motivation](articles/it-hurts-to-watch-ai-grep-my-scala.md) — why SemanticDB matters

## Project & Development

- [Development](project/development.md) — repository layout, build, test, cross-version
- [Design decisions](project/design.md) — implementation approach and extension points
- [ADR 0001: Claude Code guard hook](adr/0001-claude-code-guard-hook.md) — why setup installs a hook that denies text tools on `.scala` sources
- [Releasing](project/releasing.md) — Sonatype Central release process
- [Property-based testing audit](testing/pb-audit.md) — conversion candidates for property/golden tests

## Research & Planning

- [Claude interaction study](research/claude-interaction-study.md) — measured tool usage, tool recommendations
- [Token metrics methodology](research/token-metrics-methodology.md) — measurement definitions and generated results
- [Token metrics findings (live run)](research/token-metrics-findings.md) — end-to-end agent token usage, coverage, and limitations
- [Compat fixture sources](research/compat-fixtures-sources.md) — reusable Scala/SemanticDB fixture corpora
- [sbt build inventory](research/sbt-build-inventory.md) — baseline for sbt-to-Mill comparison
- [Docs audit](audit-results.md) — pre-shrink catalog and duplication map
- [Docs prioritization plan](prioritization-plan.md) — shrink budgets, canonical homes, and merge plan
