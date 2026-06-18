# ScalaSemanticMCP

MCP server doing deep semantic analysis on Scala projects via SemanticDB — capabilities beyond standard LSP/Metals.

## Stack
- Scala 3.8.4, sbt 2.0.0
- `org.scalameta:scalameta:4.13.9` — SemanticDB protobuf API (`scala.meta.internal.semanticdb`)
- `com.lihaoyi:upickle:4.2.1` — JSON for MCP wire protocol
- `org.scalameta:munit:1.2.3` — tests
- `semanticdbEnabled := true` — project emits its own SemanticDB; analyzer dogfoods on this repo.

## Layout
```
src/main/scala/com/github/mercurievv/scalasemantic/
  Main.scala                       # entrypoint
  semanticdb/SemanticIndex.scala   # loads *.semanticdb, indexes symbols + occurrences
```
SemanticDB output: `target/out/jvm/scala-3.8.4/scalasemanticmcp/meta/META-INF/semanticdb/**/*.semanticdb` (sbt 2.0.0 layout).

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
sbt compile      # regenerates SemanticDB for all sources
sbt test
sbt prePush      # code-quality gate before pushing
```