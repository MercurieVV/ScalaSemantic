# AGENTS.md instructions

<INSTRUCTIONS>
@SCALA_SEMANTIC_RULES.md
@CLAUDE.md
</INSTRUCTIONS>

## Quick Start

**Project**: ScalaSemantic — MCP server for deep semantic analysis on Scala projects via SemanticDB.

**Stack**: Scala 3.8.4, sbt 2.0.0, Scalameta 4.13.9, upickle 4.2.1, munit 1.2.3.

**Module Layout**:
- `core/`: Loads and indexes SemanticDB (`SemanticIndex`).
- `analysis/`: Result models and analyzer engine (depends on `core`).
- `mcp/`: JSON-RPC server and stdio entrypoint (depends on `analysis`).

**Core Commands**:
- Compile: `sbt compile` (regenerates SemanticDB)
- Test: `sbt test` (unforked tests run with cwd = repo root)
- Pre-push check: `sbt prePush` (clean, format, fix, test)
- Run MCP server: `sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <root>"`
- Worktree PR flow: `./tree2m <branch> "<commit-message>"` (creates branch, commits, pushes, merges)

**Conventions**: Follow [SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md) for Scala code rules. Use Conventional Commit titles for PRs (`feat:`, `fix:`, `perf:`).

For detailed technical instructions, architecture, and additional context, read [CLAUDE.md](CLAUDE.md).

