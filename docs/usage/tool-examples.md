---
format: mdx
---

# Tool Examples — Real Output, Self-Verifying

Every tool example on this page is **executed at docs build time** by the real Scala 3.8.4 analyzer — no hand-written JSON. If a tool call fails, the docs build fails, so this page cannot rot. Each example's exact tool/args/result triple is also pinned as a golden-file test in the `mcp` module (`DocsToolExamplesGoldenSuite` / `DocsEnrichingExamplesGoldenSuite`), so a change to tool output shows up as a reviewable diff there too, not just a silent docs rebuild.

## Quick reference

| Tool | Answers |
| --- | --- |
| **Exploration tools** | |
| `find_symbol` | Resolve a name to its definition |
| `find_usages` | All references to a symbol |
| `class_hierarchy` | Supertypes and subtypes |
| `find_overloads` | All overloads of a method |
| `members` | Declared and inherited members |
| `call_path` | Whether method A reaches method B |
| `method_call_hierarchy` | All callers or callees |
| `value_flow` | Trace a value through the call graph |
| `rename_plan` | Edit ranges for a safe rename |
| `move_plan` | Move a symbol to a new package |
| `extract_method_plan` | Extract a code range into a method |
| `structure` | Dependency graph and cycles |
| `smart_code_duplications` | Structurally identical blocks |
| **Enriching tools** | |
| `annotated_source` | Compiler view — inferred types, implicits, exploded imports, diff (tabbed) |
| `method_signature` | Full signature with implicit/using params |
| `document_outline` | File structure with compiler-rendered names |
| `resolve_implicits` | Which givens/implicits apply |
| `trace_implicit_chain` | Path of implicit dependencies |
| `type_at_position` | Type of code at a source location |

## Exploration / edit-plan tools

These tools return precise semantic answers — a symbol, a usage set, a hierarchy, an edit plan — replacing whole-file reads and grep guesswork.

### find_symbol

**Answers:** resolve a name to its definition.

Grep `transform` returns 5+ matches across comments and strings. `find_symbol` returns 1 definition.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "find_symbol",
  """{"query":"transform"}"""))
```

**Replaces:** Grepping → exact definition lookup.

---

### class_hierarchy

**Answers:** supertypes and subtypes.

```scala mdoc:passthrough
val procSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"Processor"}""")
val procData = ujson.read(procSym)
val procSymbol = if procData("count").num.toInt > 0 then procData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("class_hierarchy", s"""{"symbol":"$procSymbol"}"""))
```

**Replaces:** Reading files + grepping for extends/implements.

---

### find_overloads

**Answers:** all overloads of a method.

```scala mdoc:passthrough
val fmtSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"format"}""")
val fmtData = ujson.read(fmtSym)
val fmtSymbol = if fmtData("count").num.toInt > 0 then fmtData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("find_overloads", s"""{"symbol":"$fmtSymbol"}"""))
```

**Replaces:** Reading code for all overloads.

---

### find_usages

**Answers:** all references to a symbol.

```scala mdoc:passthrough
val useSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"transform"}""")
val useData = ujson.read(useSym)
val useSymbol = if useData("count").num.toInt > 0 then useData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("find_usages", s"""{"symbol":"$useSymbol"}"""))
```

**Replaces:** Grepping all files → exact reference list.

---

### members

**Answers:** declared and inherited members.

```scala mdoc:passthrough
val memSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"UpperProcessor"}""")
val memData = ujson.read(memSym)
val memSymbol = if memData("count").num.toInt > 0 then memData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("members", s"""{"symbol":"$memSymbol"}"""))
```

**Replaces:** Reading class + all superclass definitions.

---

### call_path

**Answers:** whether method A reaches method B.

`pipeline` never calls `process` directly, but reaches it through `compose` and `transform`. The tool returns the shortest path and the call-site of every edge.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "call_path",
  """{"from":"com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline().","to":"com/github/mercurievv/scalasemantic/docexamples/Processor#process().","detailed":true}"""))
```

**Replaces:** Manually reading through call sites to prove reachability.

---

### method_call_hierarchy

**Answers:** all callers or callees.

Outgoing from `pipeline`: `compose`, then the two `transform` calls, then `process` — the whole fan-out in one call.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "method_call_hierarchy",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline().","direction":"callees"}"""))
```

**Replaces:** Opening each callee in turn to build the tree by hand.

---

### value_flow

**Answers:** trace a value through the call graph.

The `input` parameter of `pipeline` flows into `compose`'s `input`, then `transform`'s `input`, then `process`'s `x` — a rename at every hop that text search cannot follow. The BFS also follows a value into an implicit/`using` parameter (`relation: "passed_as_implicit"`, whether the call site writes an explicit `given`/`using` arg, a `using` clause, or a context bound `[A: TC]` — all three desugar to the same implicit-parameter occurrence), terminating at `"implicit_boundary"` when that implicit has no further in-project references.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "value_flow",
  """{"file":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Navigate.scala","line":19,"column":13}"""))
```

**Replaces:** Manually chasing a value through renamed parameters across files.

---

### rename_plan

**Answers:** edit ranges for a safe rename.

The tool returns exact line and character ranges for every reference. No over-matching strings or comments.

```scala mdoc:passthrough
val renSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"transform"}""")
val renData = ujson.read(renSym)
val renSymbol = if renData("count").num.toInt > 0 then renData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("rename_plan", s"""{"symbol":"$renSymbol","newName":"apply"}"""))
```

**Replaces:** Grepping + manual editing → exact edit ranges.

---

### move_plan

**Answers:** move a symbol to a new package.

```scala mdoc:passthrough
val movSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"calculateTotal"}""")
val movData = ujson.read(movSym)
val movSymbol = if movData("count").num.toInt > 0 then movData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("move_plan", s"""{"symbol":"$movSymbol","newOwner":"com/example/math/"}"""))
```

**Replaces:** Manual refactoring and import management.

---

### extract_method_plan

**Answers:** extract a code range into a method.

The tool analyzes the range, identifies local variables and scope, returns exact edits.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "extract_method_plan",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Refactor.scala","startLine":5,"startCharacter":20,"endLine":8,"endCharacter":9}"""))
```

**Replaces:** Manual method extraction and variable management.

---

### structure

**Answers:** dependency graph and cycles.

A snapshot of entire dependency structure in one call.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty("structure", "{}"))
```

**Replaces:** Manual dependency graph construction.

---

### smart_code_duplications

**Answers:** structurally identical blocks.

The tool finds structural duplicates (same pattern, different names), ignoring syntactic noise.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "smart_code_duplications",
  """{"minSize":15}"""))
```

**Replaces:** Manual code review for duplication.

---

## Enriching tools

These tools show the LLM what the compiler sees but the source text does not — inferred types, synthesized implicit arguments and conversions, resolved signatures. Every example below runs against the same source file, executed at docs build time by the real Scala 3.8.4 analyzer.

### annotated_source

**Answers:** compiler-visible facts and inferred types — everything the compiler sees that the source text does not.

`Enrich.scala` is a small file with a `Show` typeclass, several `given` instances, a context-bound `render`, an extension method, collection calls, a `for`-comprehension, and numeric widening. The tabs run the real analyzer against it at build time:

- **Original** is the raw source.
- **compilable** shows inserted compiler facts inline.
- **symbols=on** adds symbol/package details and expands relevant wildcard `given` imports.
- **diff** shows the same enrichment as a literal patch.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
val annotatedArgs = s"""{"uri":"$enrichPath","format":"compilable","annotationsOnly":false}"""
val symbolsArgs = s"""{"uri":"$enrichPath","format":"compilable","annotationsOnly":false,"symbols":true}"""
val diffArgs = s"""{"uri":"$enrichPath","format":"diff","annotationsOnly":false,"symbols":true}"""
val annotatedRaw = scalasemantic.docs.ToolRunner.run("annotated_source", annotatedArgs)
val symbolsRaw = scalasemantic.docs.ToolRunner.run("annotated_source", symbolsArgs)
val diffRaw = scalasemantic.docs.ToolRunner.run("annotated_source", diffArgs)
```

<Tabs groupId="annotated-source">
<TabItem value="original" label="Original">

The source exactly as written.

```scala mdoc:passthrough
println(s"```scala\n${scalasemantic.docs.ToolRunner.readSource(enrichPath)}\n```")
```

</TabItem>
<TabItem value="compilable" label="compilable" default>

Compiler insertions, highlighted inline.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.requestMarkdown("annotated_source", annotatedArgs))
```

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.enrichedComponent(annotatedRaw))
```

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.detailsMarkdown(annotatedArgs, annotatedRaw))
```

</TabItem>
<TabItem value="symbols" label="symbols=on">

Inline enrichment plus symbol details.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.requestMarkdown("annotated_source", symbolsArgs))
```

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.enrichedComponent(symbolsRaw))
```

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.detailsMarkdown(symbolsArgs, symbolsRaw))
```

</TabItem>
<TabItem value="diff" label="diff (patch)">

Unified diff from source to enriched source.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.requestMarkdown("annotated_source", diffArgs))
```

```scala mdoc:passthrough
println(s"```diff\n${scalasemantic.docs.ToolRunner.extractField(diffRaw, "source")}\n```")
```

</TabItem>
</Tabs>

**Replaces:** reading the source and hand-tracing every implicit/inferred insertion → one compiler-visible view where green marks exactly what the compiler added.

---

### method_signature

**Answers:** full signature with implicit/using params.

The `render` calls in the source read `render(List(1, 2, 3))` — the `Show` instance is invisible there. The signature makes the whole contract explicit.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""))
```

**Replaces:** Reading the definition and hand-tracing the implicit list → one resolved signature.

---

### document_outline

**Answers:** file structure with compiler-rendered names.

The tool returns a tree with compiler-rendered names instead of a text scan. For a 50-line file, the outline is 5–10 lines; for 1000 lines, still manageable.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty("document_outline", s"""{"uri":"$enrichPath"}"""))
```

**Replaces:** Scanning files → structured outline.

---

### type_at_position

**Answers:** type of code at a source location.

No inference needed by hand; the tool returns the exact type the compiler assigned. For complex generics and implicit resolution, invaluable.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "type_at_position", s"""{"uri":"$enrichPath","line":14,"character":6}"""))
```

**Replaces:** Hand type inference → compiler's answer.

---

### resolve_implicits

**Answers:** which givens/implicits apply.

For `Show[_]`, two givens qualify: `intShow` directly and `listShow` (itself parameterized on another `Show`).

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "resolve_implicits",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
```

**Replaces:** Guessing which given applies → the compiler's candidate set.

---

### trace_implicit_chain

**Answers:** path of implicit dependencies.

`listShow` produces `Show[List[A]]` only by depending on a `Show[A]`; the chain makes that dependency explicit.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "trace_implicit_chain",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
```

**Replaces:** Manually following each given's own implicit needs → the whole chain.

---

### Tools on modified code

The tools above read the last compiled SemanticDB. But ScalaSemantic can also answer against a buffer that was **edited but never recompiled**: pass the current file text as `source` and the presentation compiler regenerates the analysis in memory. This is what makes the tools correct on a dirty working buffer.

Below, the only change to `Enrich.scala` is a new `prefix: String` using-parameter on `render`:

```diff
-def render[A](a: A)(using sh: Show[A]): String =
-  sh.show(a)
+def render[A](a: A)(using sh: Show[A], prefix: String): String =
+  prefix + sh.show(a)
```

`method_signature` runs on the same `render` symbol, with the same arguments, three ways: against the committed index, against the unmodified file through the presentation compiler (proving the two agree), and against the edited buffer — which reports the new parameter **without any recompile**.

```scala mdoc:passthrough
val modSigArgs =
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""
println(scalasemantic.docs.ToolRunner.requestMarkdown("method_signature", modSigArgs))
```

**DB (committed)**

```scala mdoc:passthrough
val dbRaw = scalasemantic.docs.ToolRunner.run("method_signature", modSigArgs)
println(s"```scala\n${scalasemantic.docs.ToolRunner.extractField(dbRaw, "signature")}\n```")
```

**PC (same code)**

```scala mdoc:passthrough
val pcSameRaw = scalasemantic.docs.ToolRunner.runWithSource(
  "method_signature", modSigArgs, enrichPath, enrichPath)
println(s"```scala\n${scalasemantic.docs.ToolRunner.extractField(pcSameRaw, "signature")}\n```")
```

**PC (modified)**

```scala mdoc:passthrough
val pcModRaw = scalasemantic.docs.ToolRunner.runWithSource(
  "method_signature", modSigArgs, enrichPath, "docExamples/edited/Enrich_modified.scala")
println(s"```scala\n${scalasemantic.docs.ToolRunner.extractField(pcModRaw, "signature")}\n```")
```

**Replaces:** Recompiling just to ask a question about half-finished code.
