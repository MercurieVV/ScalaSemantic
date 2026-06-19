# FAQ

## Why SemanticDB instead of parsing source files?

SemanticDB is compiler-emitted data. It includes resolved symbols, owners, types, synthetic
information, and positions after the compiler has done name resolution. That makes queries like "where
is this method called?" or "which concrete class implements this trait?" much more precise than grep
or raw syntax parsing.

## Why must I compile first?

The server reads existing `*.semanticdb` files; it does not invoke the Scala compiler. If you change
code, re-run `compile` so SemanticDB reflects the new program. If the server is already running,
restart the MCP session so it reloads the index.

## Does this replace Metals or an LSP server?

No. Metals is editor-facing and optimized for live coding features: completions, diagnostics,
go-to-definition, worksheets, and so on. This server is agent-facing: it gives an AI tool precise,
project-wide semantic queries without needing an editor cursor.

## Which install option should I choose?

For sbt projects, use [Integration](INTEGRATION.md) Option A. It enables SemanticDB and generates the
`.mcp.json` entry. For non-sbt projects, use Option B if you want the launcher to download/cache the
jar, or Option C if you want to manage the jar path yourself.

## What is Presentatin Compiler. Why and how it used here with Semantic DB?
