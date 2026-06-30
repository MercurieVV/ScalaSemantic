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
- `analysis/`: Result models and analyzer engine (depends on `core`). **Default module for ambiguous tasks.**
- `mcp/`: JSON-RPC server and stdio entrypoint (depends on `analysis`).

**Core Commands**:
- Compile + SemanticDB: `sbt --error compile` (run first — required before any semantic analysis)
- Test: `sbt --error test`
- Pre-push check: `sbt prePush` (clean, format, fix, test — full gate)
- Run MCP server: `sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <root>"`
- Worktree PR flow: `./tree2m --auto <branch> "<commit-message>"` (commits, pushes, enters merge queue)

**Startup sequence** (always in your assigned worktree):
1. `cd <worktree-path>` — stay here, never touch main checkout
2. `sbt --error compile` — must run before semantic analysis tools
3. Implement → `sbt --error test` → `./tree2m --auto <branch> "<message>"`

**Conventions**: Follow [SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md). Conventional Commit PR titles (`feat:`, `fix:`, `perf:`). Default module scope: `analysis/`.

For detailed technical instructions and architecture, read [CLAUDE.md](CLAUDE.md).

