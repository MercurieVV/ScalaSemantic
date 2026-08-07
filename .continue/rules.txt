# Project Rules & Context

MUST follow the rules in [SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md) for working with Scala code. Mandatory, not optional.

## Project Description & Startup Context

For detailed instructions, architecture, build/test commands, and conventions, please read [CLAUDE.md](CLAUDE.md) on startup.

### Quick Reference (from CLAUDE.md):
- **Project**: ScalaSemantic — MCP server for deep semantic analysis on Scala projects via SemanticDB.
- **Stack**: Scala 3.8.4, sbt 2.0.0, Scalameta 4.13.9, upickle 4.2.1, munit 1.2.3.
- **Layout**:
  - `core/`: Loads and indexes SemanticDB (`SemanticIndex`).
  - `analysis/`: Result models and analyzer engine (depends on `core`).
  - `mcp/`: JSON-RPC server and stdio entrypoint (depends on `analysis`).
- **Core Commands**:
  - Compile: `sbt compile` (regenerates SemanticDB)
  - Test: `sbt test` (unforked tests run with cwd = repo root)
  - Pre-push check: `sbt prePush` (clean, format, fix, test)
  - Run MCP server: `sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <root>"`
- **Conventions**:
  - Follow Scala Code Rules in [SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md).
  - Use Conventional Commit titles for squash merging PRs (`feat:`, `fix:`, `perf:`).

