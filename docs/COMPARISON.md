# ScalaSemantic vs `grep` (and a note on Metals/LSP)

`grep` (ripgrep, IDE text search) is the tool an agent reaches for by default. ScalaSemantic isn't a
replacement for it — it's the semantic complement. This page is the honest trade-off: where each wins.

## What ScalaSemantic does better

- **Exact symbols, no false hits.** `find_usages` on `pkg/Foo#bar().` returns *that* method — not
  every `bar` in the repo, not a `bar` in a comment, not an unrelated overload. grep can't tell them
  apart.
- **No false negatives from naming.** Import aliases, backtick-escaped names, and shadowing all resolve
  to the same symbol; grep misses renamed-on-import references and over-matches common names.
- **Relationships grep simply can't express.** Subtypes across the whole index (`class_hierarchy`),
  which givens produce a type (`resolve_implicits`) and their transitive deps (`trace_implicit_chain`),
  the shortest call path between two methods (`call_path`), declared-vs-inherited members. These are
  graph queries over the compiled program, not text patterns.
- **Type-aware signatures.** `method_signature` renders type params and flags `implicit`/`using`
  parameter lists — information that isn't in the source text in a greppable form.

## What `grep` does better

- **Zero setup, instant.** No compile, no SemanticDB, no JVM server. Works on a fresh checkout.
- **Works on *any* text.** Comments, string literals, log messages, TODOs, build files, YAML, other
  languages — anything ScalaSemantic can't see because it only knows compiled Scala symbols.
- **Always current.** Matches the bytes on disk right now; never stale. ScalaSemantic only sees what
  the last `compile` emitted.
- **Tolerates broken code.** Finds text in code that doesn't compile; SemanticDB needs a successful
  compile.
- **Ubiquitous and scriptable.** Every machine has it; trivial to pipe and combine.

## Rule of thumb

| Question | Reach for |
|---|---|
| "Where does this string / comment / TODO appear?" | `grep` |
| "Something in a config or non-Scala file" | `grep` |
| "The code doesn't compile yet" | `grep` |
| "Every caller of *this exact* method" | `find_usages` |
| "Who extends this trait?" / "which givens produce `T`?" | `class_hierarchy` / `resolve_implicits` |
| "Path from method `a` to method `c`" | `call_path` |

The server's `initialize` instructions tell the agent to prefer the semantic tools for the second
group and fall back to text search for the first.

## Limitations (read before trusting an answer)

- **Index freshness.** Results reflect the last SemanticDB-emitting `compile`; the index loads once at
  startup. Recompile to see new code.
- **Compiled Scala only.** No comments, strings, generated-but-not-compiled, or non-Scala files.
- **Some approximations.** `call_path` attributes a call to the nearest preceding method definition in
  source order (fine for flat bodies, weaker for deeply nested local defs); `linearize` is a depth-first
  parent walk, not the exact Scala 3 linearization; type rendering is best-effort and can fall back to
  partial output on exotic types.
- **Candidate-level implicits.** `resolve_implicits` lists givens that *could* produce a type; it does
  not reproduce the compiler's exact selection/priority at a specific call site.

## And Metals/LSP?

Different shape, not really a competitor: Metals is **cursor-based** (go-to-def, find-refs, hover at a
position) with a live presentation compiler. ScalaSemantic is **index-wide and headless** — it answers
questions about the whole program as data, over MCP, with no editor or cursor. Key things it gives that
a single LSP request doesn't: index-wide known subtypes, implicit/given resolution as a query, the
implicit dependency graph, and shortest call paths. Metals stays ahead on live freshness and editor
integration.

## Reproducing

Every capability is backed by a test dogfooded on this repo's own SemanticDB
([`AnalyzerSuite`](../analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/AnalyzerSuite.scala),
`McpSuite`, `CompatSuite`):

```
sbt test
```
