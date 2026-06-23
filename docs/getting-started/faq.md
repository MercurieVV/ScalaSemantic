# FAQ

## Why SemanticDB instead of parsing source files?

SemanticDB is compiler-emitted data. It includes resolved symbols, owners, types, synthetic
information, and positions after the compiler has done name resolution.

Text search sees bytes, so it cannot tell these cases apart. A grep-style search for `Animal` would
return all four lines:

```sh
$ grep -n Animal Example.scala
1:trait Animal
2:final class Dog extends Animal
3:final class AnimalReport:
4:  val label = "Animal"
```

```scala mdoc:invisible
val source =
  """trait Animal
    |final class Dog extends Animal
    |final class AnimalReport:
    |  val label = "Animal"
    |""".stripMargin

val grepOutput = source.linesIterator.zipWithIndex.collect {
  case (line, n) if line.contains("Animal") => s"${n + 1}:$line"
}.mkString("\n")

assert(grepOutput.contains("1:trait Animal"))
assert(grepOutput.contains("2:final class Dog extends Animal"))
assert(grepOutput.contains("3:final class AnimalReport:"))
assert(grepOutput.contains("""4:  val label = "Animal""""))
```

A semantic query can ask for the exact symbol `Animal#`, then follow compiler-resolved relationships
such as "known subtypes" or "exact references". That is why questions like "where is this method
called?" or "which concrete class implements this trait?" are much more precise than grep or raw
syntax parsing.

## Why must I compile first?

The server reads existing `*.semanticdb` files; it does not invoke the Scala compiler. If you change
code, re-run `compile` so SemanticDB reflects the new program. If the server is already running,
restart the MCP session so it reloads the index.

## Does this replace Metals or an LSP server?

No. Metals is editor-facing and optimized for live coding features: completions, diagnostics,
go-to-definition, worksheets, and so on. This server is agent-facing: it gives an AI tool precise,
project-wide semantic queries without needing an editor cursor.

## Which install option should I choose?

For sbt projects, use [Integration](integration.md) Option A. It enables SemanticDB and generates the
`.mcp.json` entry. For non-sbt projects, use Option B if you want the launcher to download/cache the
jar, or Option C if you want to manage the jar path yourself.

## What is the presentation compiler, and how is it used with SemanticDB?

The presentation compiler is the same kind of compiler service that editors use for live feedback. It
can typecheck an in-memory buffer and recover useful information even when the file is not cleanly
compiled yet.

ScalaSemantic's main index still comes from disk SemanticDB emitted by `compile`. The presentation
compiler is used as a second backend for position-local questions, such as `type_at_position`, when
the MCP client sends the current source text. In that case the server can overlay fresh in-memory
SemanticDB for one edited file instead of waiting for a successful project compile.

## What difference could be in token usage and context compared with grep?

Semantic tools usually spend fewer tokens because they return the specific facts the agent asked for:
symbol names, compact locations, signatures, subtype names, or a short call path. Grep returns lines
of source text. The agent then has to read surrounding files, decide which matches are real, ignore
comments and strings, and often run more searches.

For example, `find_usages` can return:

```json
{
  "symbol": "pkg/Animal#",
  "name": "Animal",
  "referenceCount": 2,
  "references": ["src/Dog.scala:4:24", "src/Fish.scala:4:25"]
}
```

With grep, the agent may need to load every matching file and inspect each line manually. That uses
more context and is still less reliable, because text matches do not know whether `Animal` is a type,
a string literal, a comment, or a different symbol with the same name.

Grep is still cheaper and better for non-semantic questions: TODOs, comments, log messages, config
files, docs, and broken code that has not compiled yet.
