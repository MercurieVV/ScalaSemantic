# AGENTS.md instructions

<INSTRUCTIONS>
@SCALA_SEMANTIC_RULES.md
@CLAUDE.md
</INSTRUCTIONS>

## Quick Start

**Project**: ScalaSemantic — MCP server for deep semantic analysis on Scala projects via SemanticDB.

**Stack**: Scala 3.8.4, Mill 1.1.7 (`build.mill`; `build.sbt`/`project/` are gone, do not use `sbt`), Scalameta 4.13.9, upickle 4.2.1, munit 1.2.3.

**Module Layout** (layer per module, package base `com.github.mercurievv.scalasemantic`):
- `core/`: Loads and indexes SemanticDB (`SemanticIndex`).
- `analysis/`: Result models and analyzer engine (depends on `core`). **Default module for ambiguous tasks.**
- `mcp/`: JSON-RPC server and stdio entrypoint (depends on `analysis`).
- Also present: `pc/`, `launcher/`, `docExamples/`, `compatFixtures/` — see [CLAUDE.md](CLAUDE.md).

**Core Commands**:
- Compile + SemanticDB: `./mill __.compile` (run first — required before any semantic analysis)
- Test: `./mill __.test`
- Pre-push check: `./mill prePush` (clean, format, test all modules, stainless — full gate)
- Run MCP server: `./mill mcp.runMain com.github.mercurievv.scalasemantic.mcpServer <root>`
- Worktree PR flow: `./tree2m <branch> "<commit-message>"` (commits, pushes, waits for CI, merges)

**Startup sequence** (always in your assigned worktree):
1. `cd <worktree-path>` — stay here, never touch main checkout
2. `./mill __.compile` — must run before semantic analysis tools
3. Implement → `./mill __.test` → `./tree2m <branch> "<message>"` (pre-push checks run automatically; don't pre-run them)

**Conventions**: Follow [SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md). Conventional Commit PR titles (`feat:`, `fix:`, `perf:`). Default module scope: `analysis/`.

For detailed technical instructions and architecture, read [CLAUDE.md](CLAUDE.md).

