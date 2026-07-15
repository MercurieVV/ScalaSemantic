---
format: mdx
---

# Tool Examples — Real Output, Self-Verifying

Every tool example on this page is **executed at docs build time** by the real Scala 3.8.4 analyzer — no hand-written JSON. If a tool call fails, the docs build fails, so this page cannot rot. Each example's exact tool/args/result triple is also pinned as a golden-file test in the `mcp` module (`DocsToolExamplesGoldenSuite` / `DocsEnrichingExamplesGoldenSuite`), so a change to tool output shows up as a reviewable diff there too, not just a silent docs rebuild.

## Quick reference

| Tool | What it tells you |
| --- | --- |
| **Find & resolve symbols** | |
| `find_symbol` | Resolve a name to its definition |
| `symbol_source` | Source of ONE symbol's definition, enriched |
| `source_around_position` | Source of the definition enclosing a position |
| **References & call graph** | |
| `find_usages` | All references to a symbol (optional `contextLines` for surrounding source) |
| `call_path` | Whether method A reaches method B |
| `method_call_hierarchy` | All callers or callees |
| `value_flow` | Trace a value through the call graph |
| **Hierarchy & members** | |
| `class_hierarchy` | Supertypes and subtypes |
| `find_overloads` | All overloads of a method |
| `members` | Declared and inherited members |
| **Search & analysis** | |
| `search_text` | Scoped text/regex search over `.scala` files (string literals/comments) |
| `structure` | Dependency graph and cycles |
| `smart_code_duplications` | Structurally identical blocks |
| `document_outline` | File structure with compiler-rendered names |
| **Implicits** | |
| `resolve_implicits` | Which givens/implicits apply |
| `trace_implicit_chain` | Path of implicit dependencies |
| **Edit plans** | |
| `rename_plan` | Edit ranges for a safe rename |
| `batch_rename_plan` | Edit ranges for multiple renames, reporting range conflicts |
| `move_plan` | Move a symbol to a new package |
| `extract_method_plan` | Extract a code range into a method |
| **Compiler view** | |
| `annotated_source` | Compiler view — inferred types, implicits, exploded imports, diff (tabbed) |
| `method_signature` | Full signature with implicit/using params |
| `type_at_position` | Type of code at a source location |

## Find & resolve symbols

Resolve names to their definitions, locate enriched source and surrounding context.

### find_symbol

**What it tells you:** resolve a name to its definition.

Grep `transform` returns 5+ matches across comments and strings. `find_symbol` returns 1 definition.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "find_symbol",
  """{"query":"transform"}"""))
```

**Replaces:** Grepping → exact definition lookup.

---

### symbol_source

**Answers:** the source of ONE symbol's definition — enriched like `annotated_source`, but scoped to just that method/class/val instead of the whole file.

Same `render` symbol as below, but this time only its own signature+body come back — not `Show`, not the `given`s, not anything else in `Enrich.scala`. The gutter keeps the file's real (absolute) line numbers, so the result still tells you exactly where in the file to look.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "symbol_source",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""))
```

A dotted FQN works too, so callers who only know the name (not the SemanticDB symbol grammar) can still use the tool:

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "symbol_source",
  """{"symbol":"com.github.mercurievv.scalasemantic.docexamples.render()"}"""))
```

**Replaces:** `annotated_source` on the whole file plus manually scrolling to the one definition you actually wanted → the definition alone, absolute line numbers intact.

---

### source_around_position

**Answers:** the source of the definition ENCLOSING a source position — like `symbol_source`, but keyed by `file`+`line`+`column` (0-based) instead of a resolved symbol, for when you only have a cursor position (e.g. from `type_at_position` or a stack trace).

Position `line=27,column=40` sits inside `Show[A].show(a)` — a REFERENCE, not a definition — on `render`'s single-line body. The tool anchors to `render`'s enclosing definition rather than jumping to `Show`'s own (unrelated) definition:

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
println(scalasemantic.docs.ToolRunner.runPretty(
  "source_around_position",
  s"""{"file":"$enrichPath","line":27,"column":40,"format":"plain"}"""))
```

When no enclosing definition exists at the position (e.g. a blank line before any declaration), the tool falls back to a fixed ±15-line window and notes the fallback in `legend`.

**Replaces:** manually re-deriving "what method/class am I inside" from a line/column → the enclosing definition's source, resolved and enriched, in one call.

---

## References & call graph

Who calls what, value flow, and reachability.

### find_usages

**What it tells you:** all references to a symbol.

```scala mdoc:passthrough
val useSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"transform"}""")
val useData = ujson.read(useSym)
val useSymbol = if useData("count").num.toInt > 0 then useData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("find_usages", s"""{"symbol":"$useSymbol"}"""))
```

Pass `contextLines` to get a few lines of source around each hit — no more piping the reference
list to `rg -A5` for context. Each file backing a hit is read once, however many references it
holds.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty("find_usages", s"""{"symbol":"$useSymbol","contextLines":1}"""))
```

**Replaces:** Grepping all files → exact reference list.

---

### call_path

**What it tells you:** whether method A reaches method B.

`pipeline` never calls `process` directly, but reaches it through `compose` and `transform`. The tool returns the shortest path and the call-site of every edge.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "call_path",
  """{"from":"com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline().","to":"com/github/mercurievv/scalasemantic/docexamples/Processor#process().","detailed":true}"""))
```

**Replaces:** Manually reading through call sites to prove reachability.

---

### method_call_hierarchy

**What it tells you:** all callers or callees.

Outgoing from `pipeline`: `compose`, then the two `transform` calls, then `process` — the whole fan-out in one call.

```scala mdoc:passthrough
val callHierarchyArgs =
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline().","direction":"callees"}"""
val callHierarchyRaw =
  scalasemantic.docs.ToolRunner.run("method_call_hierarchy", callHierarchyArgs)
println(scalasemantic.docs.ToolRunner.requestMarkdown("method_call_hierarchy", callHierarchyArgs))
println()
println(scalasemantic.docs.ToolRunner.callHierarchyMermaid(callHierarchyRaw))
println()
println(scalasemantic.docs.ToolRunner.detailsMarkdown(callHierarchyArgs, callHierarchyRaw))
```

**Replaces:** Opening each callee in turn to build the tree by hand.

---

### value_flow

**What it tells you:** trace a value through the call graph.

The `input` parameter of `pipeline` flows into `compose`'s `input`, then `transform`'s `input`, then `process`'s `x` — a rename at every hop that text search cannot follow. The BFS also follows a value into an implicit/`using` parameter (`relation: "passed_as_implicit"`, whether the call site writes an explicit `given`/`using` arg, a `using` clause, or a context bound `[A: TC]` — all three desugar to the same implicit-parameter occurrence), terminating at `"implicit_boundary"` when that implicit has no further in-project references.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "value_flow",
  """{"file":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Navigate.scala","line":19,"column":13}"""))
```

**Replaces:** Manually chasing a value through renamed parameters across files.

---

## Hierarchy & members

Types, supertypes, subtypes, overloads, and member lists.

### class_hierarchy

**What it tells you:** supertypes and subtypes.

```scala mdoc:passthrough
val procSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"Processor"}""")
val procData = ujson.read(procSym)
val procSymbol = if procData("count").num.toInt > 0 then procData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("class_hierarchy", s"""{"symbol":"$procSymbol"}"""))
```

**Replaces:** Reading files + grepping for extends/implements.

---

### find_overloads

**What it tells you:** all overloads of a method sharing its owner, plus same-named methods
inherited from parent types (`inheritedOverloads`, each suffixed `(from <Parent>)`) — the full
overload set visible on the type, not just the ones declared locally.

```scala mdoc:passthrough
val fmtSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"format"}""")
val fmtData = ujson.read(fmtSym)
val fmtSymbol = if fmtData("count").num.toInt > 0 then fmtData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("find_overloads", s"""{"symbol":"$fmtSymbol"}"""))
```

**Replaces:** Reading code for all overloads, including a manual walk up the class hierarchy.

---

### members

**What it tells you:** declared and inherited members.

```scala mdoc:passthrough
val memSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"UpperProcessor"}""")
val memData = ujson.read(memSym)
val memSymbol = if memData("count").num.toInt > 0 then memData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("members", s"""{"symbol":"$memSymbol"}"""))
```

**Replaces:** Reading class + all superclass definitions.

---

## Search & analysis

Full-text search, structural patterns, and dependencies.

### search_text

**What it tells you:** plain text/regex hits (file + line) over `.scala` files — string literals
and comments, the one case SemanticDB has no symbol model for.

This is the sanctioned in-MCP replacement for shelling out to `grep`/`rg` on Scala sources; it is
**not** symbol-aware (no rename/re-export/implicit resolution) and can over-match comments/strings
like grep does. Use `find_symbol`/`find_usages` for identifiers instead.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "search_text",
  """{"query":"context bound","pathFilter":"*docexamples/Enrich.scala*"}"""))
```

**Replaces:** `grep`/`rg` on `.scala` files for non-symbol text, kept inside MCP tooling.

---

### structure

**What it tells you:** dependency graph and cycles.

A snapshot of the product modules' dependency structure in one call.

Metric values:

- `types`: number of in-project types in a module.
- `layer`: longest dependency-chain depth after cycles are condensed; `0` means a foundation type/module, larger numbers sit above deeper dependencies.
- `ca`: afferent coupling, the number of incoming dependencies; higher values mean more project symbols depend on this symbol/module.
- `ce`: efferent coupling, the number of outgoing dependencies; higher values mean this symbol/module depends on more project symbols.
- `instability`: `ce / (ca + ce)`, from `0` to `1`; `0` is stable/foundation-like, `1` is leaf/consumer-like.
- `centrality`: PageRank-style importance; higher values mean the symbol is more structurally central because important symbols depend on it.
- `dependencies` weight: number of semantic dependency observations collapsed into one symbol-to-symbol edge; thicker graph edges mean higher weight.
- `moduleEdges` weight: number of symbol-level dependency edges crossing from one module to another.

```scala mdoc:passthrough
val structureArgs = """{"limit":20}"""
val structureRaw = scalasemantic.docs.ToolRunner.runStructure(structureArgs)
println(scalasemantic.docs.ToolRunner.requestMarkdown("structure", structureArgs))
println()
println(scalasemantic.docs.ToolRunner.structureGraphComponent(structureRaw))
println()
println(scalasemantic.docs.ToolRunner.detailsMarkdown(structureArgs, structureRaw))
```

**Replaces:** Manual dependency graph construction.

---

### smart_code_duplications

**What it tells you:** structurally identical blocks.

The tool finds structural duplicates (same pattern, different names), ignoring syntactic noise.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "smart_code_duplications",
  """{"minSize":15}"""))
```

Pass `showSource: true` to also get a `groupsSource` field with the duplicated lines for every
occurrence, gutter-numbered like `annotated_source`'s `plain` format — handy for eyeballing what a
clone group actually looks like without a second `annotated_source` round trip.

**Replaces:** Manual code review for duplication.

---

### document_outline

**What it tells you:** file structure with compiler-rendered names.

The tool returns a tree with compiler-rendered names instead of a text scan. For a 50-line file, the outline is 5–10 lines; for 1000 lines, still manageable.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val outlineArgs = s"""{"uri":"$enrichPath"}"""
println(scalasemantic.docs.ToolRunner.requestMarkdown("document_outline", outlineArgs))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val outlineArgs = s"""{"uri":"$enrichPath"}"""
val outlineRaw = scalasemantic.docs.ToolRunner.run("document_outline", outlineArgs)
println(scalasemantic.docs.ToolRunner.outlineMermaid(outlineRaw))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val outlineArgs = s"""{"uri":"$enrichPath"}"""
val outlineRaw = scalasemantic.docs.ToolRunner.run("document_outline", outlineArgs)
println(scalasemantic.docs.ToolRunner.detailsMarkdown(outlineArgs, outlineRaw))
```

**Replaces:** Scanning files → structured outline.

---

## Implicits

Given/implicit resolution and chains.

### resolve_implicits

**What it tells you:** which givens/implicits apply.

`resolve_implicits` returns the flat candidate set: every given/implicit in the index that can produce some `Show[X]`. For `Show#`, that includes `intShow`, `stringShow`, `listShow`, and the imported `doubleShow`/`floatShow` instances. This is the candidate set before a call-site selection, not a scope-filtered proof that one instance was applied; `chosen` is filled only when exactly one candidate exists.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "resolve_implicits",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
```

**Replaces:** Guessing which givens exist → the compiler's candidate set.

---

### trace_implicit_chain

**What it tells you:** path of implicit dependencies.

`trace_implicit_chain` returns the same candidate set plus each candidate's own implicit dependencies. `listShow` produces `Show[List[A]]` only given a `Show[A]`, so its step lists `Show#` as a dependency; that dependency edge is what distinguishes it from `resolve_implicits`.

Use `resolve_implicits` when you need the flat candidate set; use `trace_implicit_chain` when you also need the transitive dependencies each candidate would require.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "trace_implicit_chain",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
```

For a concrete wanted type, pass `appliedType`. Here `Show[List[Int]]` resolves to `listShow`, whose nested dependency resolves to `intShow`.

```scala mdoc:passthrough
val concreteImplicitArgs =
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#","appliedType":"Show[List[Int]]"}"""
println(scalasemantic.docs.ToolRunner.requestMarkdown("trace_implicit_chain", concreteImplicitArgs))
```

```scala mdoc:passthrough
val concreteImplicitArgs =
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#","appliedType":"Show[List[Int]]"}"""
val concreteImplicitRaw =
  scalasemantic.docs.ToolRunner.run("trace_implicit_chain", concreteImplicitArgs)
println(scalasemantic.docs.ToolRunner.implicitTreeMermaid(concreteImplicitRaw))
```

```scala mdoc:passthrough
val concreteImplicitArgs =
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#","appliedType":"Show[List[Int]]"}"""
val concreteImplicitRaw =
  scalasemantic.docs.ToolRunner.run("trace_implicit_chain", concreteImplicitArgs)
println(scalasemantic.docs.ToolRunner.detailsMarkdown(concreteImplicitArgs, concreteImplicitRaw))
```

**Replaces:** Manually following each given's own implicit needs → the whole chain.

---

## Edit plans

Refactoring assistance: rename, move, extract.

### rename_plan

**What it tells you:** edit ranges for a safe rename.

The tool returns exact line and character ranges for every reference. No over-matching strings or comments.

```scala mdoc:passthrough
val renSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"transform"}""")
val renData = ujson.read(renSym)
val renSymbol = if renData("count").num.toInt > 0 then renData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("rename_plan", s"""{"symbol":"$renSymbol","newName":"apply"}"""))
```

**Replaces:** Grepping + manual editing → exact edit ranges.

---

### batch_rename_plan

**What it tells you:** edit ranges for renaming multiple symbols in one request, so a project-wide
rename batch doesn't get done by hand one `rename_plan` call at a time.

`transform` and `calculateTotal` live in different files and never touch the same edit range, so
`combinedEditCount` is just the sum of the two individual plans' edit counts and `conflictCount` is
`0`. The tool only detects literal edit-**range** overlaps between the requested renames — two
different symbols renamed to the same new name (a semantic collision at a call site) is out of
scope and remains the caller's responsibility.

```scala mdoc:passthrough
val batchSym1 = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"transform"}""")
val batchData1 = ujson.read(batchSym1)
val batchSymbol1 = if batchData1("count").num.toInt > 0 then batchData1("symbols")(0)("symbol").str else "unknown"
val batchSym2 = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"calculateTotal"}""")
val batchData2 = ujson.read(batchSym2)
val batchSymbol2 = if batchData2("count").num.toInt > 0 then batchData2("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty(
  "batch_rename_plan",
  s"""{"renames":[{"symbol":"$batchSymbol1","newName":"apply"},{"symbol":"$batchSymbol2","newName":"total"}]}"""))
```

**Replaces:** Running `rename_plan` once per symbol and manually reconciling edits by hand.

---

### move_plan

**What it tells you:** move a symbol to a new package.

```scala mdoc:passthrough
val movSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"calculateTotal"}""")
val movData = ujson.read(movSym)
val movSymbol = if movData("count").num.toInt > 0 then movData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("move_plan", s"""{"symbol":"$movSymbol","newOwner":"com/example/math/"}"""))
```

**Replaces:** Manual refactoring and import management.

---

### extract_method_plan

**What it tells you:** extract a code range into a method.

The tool analyzes the range, identifies local variables and scope, returns exact edits.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "extract_method_plan",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Refactor.scala","startLine":5,"startCharacter":20,"endLine":8,"endCharacter":9}"""))
```

**Replaces:** Manual method extraction and variable management.

---

## Compiler view

Inferred types, implicit insertions, signatures, and types at positions.

### annotated_source

**What it tells you:** compiler-visible facts and inferred types — everything the compiler sees that the source text does not.

`Enrich.scala` is a small file with a `Show` typeclass, several `given` instances, a context-bound `render`, an extension method, collection calls, a `for`-comprehension, and numeric widening. The tabs run the real analyzer against it at build time:

- **compilable** shows compiler insertions as a patch against the original source.
- **symbols=on** adds symbol/package details and expands relevant wildcard `given` imports as a patch.
- **docs=strip** shows comment stripping and compiler insertions as a patch.
- **All (diff)** shows every enrichment against the original as a literal patch.
- **Original** is the raw source.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val fixedArgs      = s"""{"uri":"$enrichPath","annotationsOnly":false}"""
val compilableArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false}"""
val symbolsArgs    = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"symbols":true}"""
val docsStripArgs  = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"docs":"strip"}"""
val allDiffArgs    = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"symbols":true,"detail":"full","docs":"keep"}"""
```

The request below is shared by every tab; each tab lists only the argument values it changes.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val fixedArgs      = s"""{"uri":"$enrichPath","annotationsOnly":false}"""
println(scalasemantic.docs.ToolRunner.commonRequestMarkdown("annotated_source", fixedArgs))
```

<Tabs groupId="annotated-source">
<TabItem value="compilable" label="compilable" default>

Compiler insertions as a patch.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val compilableArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false}"""
val compilableRaw = scalasemantic.docs.ToolRunner.run("annotated_source", compilableArgs)
println(scalasemantic.docs.ToolRunner.variantLine("compilable", """{"format":"diff"}"""))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val compilableArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false}"""
val compilableRaw = scalasemantic.docs.ToolRunner.run("annotated_source", compilableArgs)
println(scalasemantic.docs.ToolRunner.wordDiffComponent(
  scalasemantic.docs.ToolRunner.extractField(compilableRaw, "source")))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val compilableArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false}"""
val compilableRaw = scalasemantic.docs.ToolRunner.run("annotated_source", compilableArgs)
println(scalasemantic.docs.ToolRunner.detailsMarkdown(compilableArgs, compilableRaw))
```

</TabItem>
<TabItem value="symbols" label="symbols=on">

Inline enrichment plus symbol details as a patch.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val symbolsArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"symbols":true}"""
val symbolsRaw = scalasemantic.docs.ToolRunner.run("annotated_source", symbolsArgs)
println(scalasemantic.docs.ToolRunner.variantLine("symbols=on", """{"format":"diff","symbols":true}"""))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val symbolsArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"symbols":true}"""
val symbolsRaw = scalasemantic.docs.ToolRunner.run("annotated_source", symbolsArgs)
println(scalasemantic.docs.ToolRunner.wordDiffComponent(
  scalasemantic.docs.ToolRunner.extractField(symbolsRaw, "source")))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val symbolsArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"symbols":true}"""
val symbolsRaw = scalasemantic.docs.ToolRunner.run("annotated_source", symbolsArgs)
println(scalasemantic.docs.ToolRunner.detailsMarkdown(symbolsArgs, symbolsRaw))
```

</TabItem>
<TabItem value="docs-strip" label="docs=strip">

Compiler insertions with comments removed, shown as a patch.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val docsStripArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"docs":"strip"}"""
val docsStripRaw = scalasemantic.docs.ToolRunner.run("annotated_source", docsStripArgs)
println(scalasemantic.docs.ToolRunner.variantLine("docs=strip", """{"format":"diff","docs":"strip"}"""))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val docsStripArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"docs":"strip"}"""
val docsStripRaw = scalasemantic.docs.ToolRunner.run("annotated_source", docsStripArgs)
println(scalasemantic.docs.ToolRunner.wordDiffComponent(
  scalasemantic.docs.ToolRunner.extractField(docsStripRaw, "source")))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val docsStripArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"docs":"strip"}"""
val docsStripRaw = scalasemantic.docs.ToolRunner.run("annotated_source", docsStripArgs)
println(scalasemantic.docs.ToolRunner.detailsMarkdown(docsStripArgs, docsStripRaw))
```

</TabItem>
<TabItem value="all-diff" label="All (diff)">

Unified diff from source to enriched source.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val allDiffArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"symbols":true,"detail":"full","docs":"keep"}"""
val allDiffRaw = scalasemantic.docs.ToolRunner.run("annotated_source", allDiffArgs)
println(scalasemantic.docs.ToolRunner.variantLine("All (diff)", """{"format":"diff","symbols":true,"detail":"full","docs":"keep"}"""))
```

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val allDiffArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"symbols":true,"detail":"full","docs":"keep"}"""
val allDiffRaw = scalasemantic.docs.ToolRunner.run("annotated_source", allDiffArgs)
println(scalasemantic.docs.ToolRunner.wordDiffComponent(
  scalasemantic.docs.ToolRunner.extractField(allDiffRaw, "source")))
```

</TabItem>
<TabItem value="original" label="Original">

The source exactly as written.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
println(s"```scala\n${scalasemantic.docs.ToolRunner.readSource(enrichPath)}\n```")
```

</TabItem>
</Tabs>

**Replaces:** reading the source and hand-tracing every implicit/inferred insertion → one compiler-visible view where green marks exactly what the compiler added.

---

### method_signature

**What it tells you:** full signature with implicit/using params.

The `render` calls in the source read `render(List(1, 2, 3))` — the `Show` instance is invisible there. The signature makes the whole contract explicit.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""))
```

**Replaces:** Reading the definition and hand-tracing the implicit list → one resolved signature.

---

### type_at_position

**What it tells you:** type of code at a source location.

The source writes no type annotation on `ranked`; the compiler inferred `List[(String, Int)]`. `type_at_position` returns exactly that — no hand-inference.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
println(scalasemantic.docs.ToolRunner.runPretty(
  "type_at_position", s"""{"uri":"$enrichPath","line":47,"character":4}"""))
```

**Replaces:** Hand type inference → compiler's answer.

---

## Tools on modified code

The tools above read the last compiled SemanticDB. But ScalaSemantic can also answer against a buffer that was **edited but never recompiled**: pass the current file text as `source` and the presentation compiler regenerates the analysis in memory. This is what makes the tools correct on a dirty working buffer.

Below, the only change to `Enrich.scala` is a new `prefix: String` using-parameter on `render`:

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.wordDiffComponent(
  """-def render[A](a: A)(using sh: Show[A]): String =
-  sh.show(a)
+def render[A](a: A)(using sh: Show[A], prefix: String): String =
+  prefix + sh.show(a)"""))
```

`method_signature` runs on the same `render` symbol, with the same arguments, three ways: against the committed index, against the unmodified file through the presentation compiler (proving the two agree), and against the edited buffer — which reports the new parameter **without any recompile**.

```scala mdoc:passthrough
val modSigArgs =
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""
println(scalasemantic.docs.ToolRunner.requestMarkdown("method_signature", modSigArgs))
```

**DB (committed)**

```scala mdoc:passthrough
val modSigArgs =
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""
val dbRaw = scalasemantic.docs.ToolRunner.run("method_signature", modSigArgs)
println(scalasemantic.docs.ToolRunner.syntaxComponent(
  scalasemantic.docs.ToolRunner.extractField(dbRaw, "signature")))
```

**PC (same code)**

```scala mdoc:passthrough
val modSigArgs =
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val pcSameRaw = scalasemantic.docs.ToolRunner.runWithSource(
  "method_signature", modSigArgs, enrichPath, enrichPath)
println(scalasemantic.docs.ToolRunner.syntaxComponent(
  scalasemantic.docs.ToolRunner.extractField(pcSameRaw, "signature")))
```

**PC (modified)**

```scala mdoc:passthrough
val modSigArgs =
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val dbRaw = scalasemantic.docs.ToolRunner.run("method_signature", modSigArgs)
val pcModRaw = scalasemantic.docs.ToolRunner.runWithSource(
  "method_signature", modSigArgs, enrichPath, "docExamples/edited/Enrich_modified.scala")
val before = scalasemantic.docs.ToolRunner.extractField(dbRaw, "signature")
val after  = scalasemantic.docs.ToolRunner.extractField(pcModRaw, "signature")
println(scalasemantic.docs.ToolRunner.wordDiffComponent(s"-$before\n+$after"))
```

**Replaces:** Recompiling just to ask a question about half-finished code.
