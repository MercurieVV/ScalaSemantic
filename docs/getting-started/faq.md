# FAQ

## Why SemanticDB instead of parsing source files?

SemanticDB is compiler-emitted data: resolved symbols, owners, types, and positions after name resolution. Text search sees bytes and cannot tell these apart:

```sh
$ grep -n Animal Example.scala
1:trait Animal
2:final class Dog extends Animal
3:final class AnimalReport:
4:  val label = "Animal"
```

A semantic query on symbol `Animal#` follows compiler-resolved relationships — known subtypes, exact references — and never matches `AnimalReport` or the string literal. That is why "where is this method called?" or "which concrete class implements this trait?" are much more precise with semantic tools.

## Why must I compile first?

The server reads existing `*.semanticdb` files; it does not invoke the compiler. If you change code, re-run `compile` so SemanticDB reflects the new program. If the MCP session is already running, restart it so the server reloads the index.

## Does this replace Metals or an LSP server?

No. Metals is editor-facing and optimized for live coding: completions, diagnostics, go-to-definition, worksheets. ScalaSemantic is agent-facing: it gives an AI tool precise, project-wide semantic queries without needing an editor cursor. Key additions over a single LSP request: index-wide known subtypes, implicit/given resolution as a query, the implicit dependency graph, and shortest call paths.

## Which install option should I choose?

For sbt projects, use Option A (sbt plugin) from the [Integration guide](integration.md) — it enables SemanticDB and generates the client config automatically. For non-sbt projects, use Option B (auto-download launcher) or Option C (plain `java -jar`).

## What is the presentation compiler, and how is it used?

The presentation compiler is the live compiler service that editors use for completions and hover. ScalaSemantic's main index comes from disk SemanticDB emitted by `compile`. The presentation compiler is used as a second backend for position-local questions such as `type_at_position` when the MCP client sends the current source text — the server overlays fresh in-memory SemanticDB for the edited file without waiting for a project compile.

## How much does token usage differ from grep?

Significantly — semantic tools typically return 80–95% fewer tokens for symbol questions. `find_usages` returns a compact list of exact locations; grep returns every line that textually matches, including false hits that require opening files to disambiguate. Full analysis with measured numbers: [SemanticDB vs grep](../explanation/scala-semantic-vs-grep.md).
