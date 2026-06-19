# ScalaSemantic

MCP server doing deep semantic analysis on Scala projects via SemanticDB — capabilities beyond standard LSP/Metals.

## Stack
- Scala 3.8.4, sbt 2.0.0
- `org.scalameta:scalameta:4.13.9` — SemanticDB protobuf API (`scala.meta.internal.semanticdb`)
- `com.lihaoyi:upickle:4.2.1` — JSON for MCP wire protocol
- `org.scalameta:munit:1.2.3` — tests
- `semanticdbEnabled := true` — project emits its own SemanticDB; analyzer dogfoods on this repo.

## Layout
Three sbt modules (layer per module), package base `com.github.mercurievv.scalasemantic`:
```
core/      …​.semanticdb   SemanticIndex — loads/indexes *.semanticdb (no JSON, no MCP)
analysis/  …​.analysis, …​.model   Analyzer + upickle result models; dependsOn core
mcp/       …​.mcp, Main    stdio JSON-RPC server + entrypoint; dependsOn analysis (test->test too)
```
SemanticDB is emitted per module under `<module>/target/out/.../meta/META-INF/semanticdb/**`.
Dogfood tests load `SemanticIndex.fromProject(".")` (repo root) so they see every module's output;
unforked tests run with cwd = repo root. `mcp` test-depends on `analysis` so fixtures compile first.

## Architecture
3 layers: **MCP stdio JSON-RPC** → **analysis engine** → **SemanticIndex**.
- No Scala MCP SDK exists — JSON-RPC is hand-rolled over stdin/stdout with upickle.
- Signature rendering is a custom `Type`/`Signature` printer; implicit params detected via `SymbolInformation.Property.IMPLICIT` bitmask.
- Call graph: edges from `SymbolOccurrence` references within a method's definition range; BFS for path-find.

## MCP tools (target surface)
find-usages, method-signature, class-hierarchy, resolve-implicits, trait-vs-local-members,
type-at-position, cross-file-refs, find-overloads, trace-implicit-chain, call-graph-path.

## Conventions
- Symbol strings follow SemanticDB grammar (descriptors end in `#` type, `.` term, `/` package, `(...)` method disambig).
- Result types are `upickle` case classes with derived `ReadWriter`.
- Validate every feature by dogfooding against this repo's own SemanticDB.

## Build / test
```
sbt compile      # regenerates SemanticDB for all modules
sbt test
sbt prePush      # command alias: clean; scalafmtAll; scalafixAll; Test/testOnly * (aggregates all modules)
sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <root>"  # start the server
```

## Releasing
Version is the git tag (`vX.Y.Z`); pushing a tag publishes to Maven Central via `sbt-ci-release`.
**Every release MUST update [`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md):** before tagging,
prepend a `## vX.Y.Z — <date>` section summarizing the user-facing changes since the previous tag
(derive from `git log <prevTag>..HEAD`), and commit it as part of the release. Newest first. The
release *process* is in [`docs/RELEASING.md`](docs/RELEASING.md).