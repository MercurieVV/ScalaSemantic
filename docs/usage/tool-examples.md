import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Tool Examples — Real Output, Self-Verifying

Every tool example on this page is **executed at docs build time** by the real Scala 3.8.4 analyzer. No hand-written JSON; if the tool call fails, the docs build fails. This page cannot rot.

## Quick reference

| Tool | Answers |
| --- | --- |
| **Enriching tools** | |
| `annotated_source` | Compiler-visible facts and inferred types |
| `method_signature` | Full signature with implicit/using params |
| `document_outline` | File structure with compiler-rendered names |
| `resolve_implicits` | Which givens/implicits apply |
| `trace_implicit_chain` | Path of implicit dependencies |
| `type_at_position` | Type of code at a source location |
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

## Enriching tools

These tools show the LLM what the compiler sees but the source text does not — inferred types, synthesized implicit arguments and conversions, resolved signatures.

### annotated_source

> **Answers:** The compiler's exact view of the file: inferred types on every binding, synthesized (using) arguments, implicit conversions.

Input (`Enrich.scala`):

```scala mdoc:silent
trait Show[A]:
  def show(a: A): String

given intShow: Show[Int] with
  def show(a: Int) = a.toString

given listShow[A](using sh: Show[A]): Show[List[A]] with
  def show(a: List[A]) = a.map(sh.show).mkString("[", ", ", "]")

def render[A](a: A)(using sh: Show[A]): String =
  sh.show(a)

val out = render(List(1, 2, 3))
val num = render(42)
```

**What enrichment this adds:**

The compiler injects `(using intShow)` and `(using listShow(...))` into the `render` calls, and infers the return type `: String` on the `out` and `num` bindings — none visible in source text.

**Tool output:**

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "annotated_source",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala","format":"annotated","annotationsOnly":true}"""))
println("```")
```

**Replaces:** Reading 15 lines of source → 10 lines with compiler-visible facts.

---

### method_signature

> **Answers:** The full resolved signature of a method — including the `(using ...)` / implicit parameter lists that are written once at the definition and never at the call sites.

The `render` calls in the source read `render(List(1, 2, 3))` — the `Show` instance is invisible there. The signature makes the whole contract explicit.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""))
println("```")
```

**Replaces:** Reading the definition and hand-tracing the implicit list → one resolved signature.

---

### document_outline

> **Answers:** File outline with compiler-rendered signatures (compiler names, not source text).

The tool returns a tree with compiler-rendered names instead of a text scan. For a 50-line file, the outline is 5–10 lines; for 1000 lines, still manageable.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "document_outline",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"}"""))
println("```")
```

**Replaces:** Scanning files → structured outline.

---

### type_at_position

> **Answers:** The inferred type at a specific source location.

No inference needed by hand; the tool returns the exact type the compiler assigned. For complex generics and implicit resolution, invaluable.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "type_at_position",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala","line":14,"character":6}"""))
println("```")
```

**Replaces:** Hand type inference → compiler's answer.

---

### resolve_implicits

> **Answers:** Which given/implicit definitions can produce a wanted type — the search the compiler does at every implicit parameter, which text search cannot do.

For `Show[_]`, two givens qualify: `intShow` directly and `listShow` (itself parameterized on another `Show`).

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "resolve_implicits",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
println("```")
```

**Replaces:** Guessing which given applies → the compiler's candidate set.

---

### trace_implicit_chain

> **Answers:** The givens that produce a type **and the implicits they transitively pull in** — implicit resolution followed step by step.

`listShow` produces `Show[List[A]]` only by depending on a `Show[A]`; the chain makes that dependency explicit.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "trace_implicit_chain",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
println("```")
```

**Replaces:** Manually following each given's own implicit needs → the whole chain.

---

## Exploration / edit-plan tools

These tools return precise semantic answers — a symbol, a usage set, a hierarchy, an edit plan — replacing whole-file reads and grep guesswork.

### find_symbol

> **Answers:** The symbol (definition and fully qualified name) for a plain name in code.

Grep `transform` returns 5+ matches across comments and strings. `find_symbol` returns 1 definition.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "find_symbol",
  """{"query":"transform"}"""))
println("```")
```

**Replaces:** Grepping → exact definition lookup.

---

### class_hierarchy

> **Answers:** The supertypes and subtypes of a class or trait.

```scala mdoc:passthrough
println("```json")
val procSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"Processor"}""")
val procData = ujson.read(procSym)
val procSymbol = if procData("count").num.toInt > 0 then procData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.run("class_hierarchy", s"""{"symbol":"$procSymbol"}"""))
println("```")
```

**Replaces:** Reading files + grepping for extends/implements.

---

### find_overloads

> **Answers:** All overloads of a method.

```scala mdoc:passthrough
println("```json")
val fmtSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"format"}""")
val fmtData = ujson.read(fmtSym)
val fmtSymbol = if fmtData("count").num.toInt > 0 then fmtData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.run("find_overloads", s"""{"symbol":"$fmtSymbol"}"""))
println("```")
```

**Replaces:** Reading code for all overloads.

---

### find_usages

> **Answers:** All references to a symbol (exact, no over-matching comments or strings).

```scala mdoc:passthrough
println("```json")
val useSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"transform"}""")
val useData = ujson.read(useSym)
val useSymbol = if useData("count").num.toInt > 0 then useData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.run("find_usages", s"""{"symbol":"$useSymbol"}"""))
println("```")
```

**Replaces:** Grepping all files → exact reference list.

---

### members

> **Answers:** All declared and inherited members of a class or trait.

```scala mdoc:passthrough
println("```json")
val memSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"UpperProcessor"}""")
val memData = ujson.read(memSym)
val memSymbol = if memData("count").num.toInt > 0 then memData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.run("members", s"""{"symbol":"$memSymbol"}"""))
println("```")
```

**Replaces:** Reading class + all superclass definitions.

---

### call_path

> **Answers:** Whether method A reaches method B, and the exact call chain that connects them.

`pipeline` never calls `process` directly, but reaches it through `compose` and `transform`. The tool returns the shortest path and the call-site of every edge.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "call_path",
  """{"from":"com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline().","to":"com/github/mercurievv/scalasemantic/docexamples/Processor#process().","detailed":true}"""))
println("```")
```

**Replaces:** Manually reading through call sites to prove reachability.

---

### method_call_hierarchy

> **Answers:** The transitive callers (incoming) or callees (outgoing) of a method, as a tree.

Outgoing from `pipeline`: `compose`, then the two `transform` calls, then `process` — the whole fan-out in one call.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "method_call_hierarchy",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline().","direction":"callees"}"""))
println("```")
```

**Replaces:** Opening each callee in turn to build the tree by hand.

---

### value_flow

> **Answers:** How a value propagates through the code — following it across method boundaries into renamed parameters, and classifying where it ends up.

The `input` parameter of `pipeline` flows into `compose`'s `input`, then `transform`'s `input`, then `process`'s `x` — a rename at every hop that text search cannot follow.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "value_flow",
  """{"file":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Navigate.scala","line":19,"column":13}"""))
println("```")
```

**Replaces:** Manually chasing a value through renamed parameters across files.

---

### rename_plan

> **Answers:** Exact character ranges to rewrite for a safe rename.

The tool returns exact line and character ranges for every reference. No over-matching strings or comments.

```scala mdoc:passthrough
println("```json")
val renSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"transform"}""")
val renData = ujson.read(renSym)
val renSymbol = if renData("count").num.toInt > 0 then renData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.run("rename_plan", s"""{"symbol":"$renSymbol","newName":"apply"}"""))
println("```")
```

**Replaces:** Grepping + manual editing → exact edit ranges.

---

### move_plan

> **Answers:** Exact edits to move a top-level symbol to a different package.

```scala mdoc:passthrough
println("```json")
val movSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"calculateTotal"}""")
val movData = ujson.read(movSym)
val movSymbol = if movData("count").num.toInt > 0 then movData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.run("move_plan", s"""{"symbol":"$movSymbol","newOwner":"com/example/math/"}"""))
println("```")
```

**Replaces:** Manual refactoring and import management.

---

### extract_method_plan

> **Answers:** Edits to extract a code range into a new method.

The tool analyzes the range, identifies local variables and scope, returns exact edits.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "extract_method_plan",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Refactor.scala","startLine":5,"startCharacter":20,"endLine":8,"endCharacter":9}"""))
println("```")
```

**Replaces:** Manual method extraction and variable management.

---

### structure

> **Answers:** Project-wide dependency graph, metrics, and cycles.

A snapshot of entire dependency structure in one call.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run("structure", "{}"))
println("```")
```

**Replaces:** Manual dependency graph construction.

---

### smart_code_duplications

> **Answers:** Structurally identical code blocks (not text-matched).

The tool finds structural duplicates (same pattern, different names), ignoring syntactic noise.

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "smart_code_duplications",
  """{"minSize":15}"""))
println("```")
```

**Replaces:** Manual code review for duplication.

---

## Tools on modified code

The tools above read the last compiled SemanticDB. But ScalaSemantic can also answer against a buffer that was **edited but never recompiled**: pass the current file text as `source` and the presentation compiler regenerates the analysis in memory. This is what makes the tools correct on a dirty working buffer.

Below, the only change to `Enrich.scala` is a new `prefix: String` using-parameter on `render`:

```diff
-def render[A](a: A)(using sh: Show[A]): String =
-  sh.show(a)
+def render[A](a: A)(using sh: Show[A], prefix: String): String =
+  prefix + sh.show(a)
```

The three tabs run `method_signature` on the same `render` symbol: against the committed index, against the unmodified file through the presentation compiler (proving the two agree), and against the edited buffer — which reports the new parameter **without any recompile**.

<Tabs>
<TabItem value="db" label="DB (committed)" default>

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""))
println("```")
```

</TabItem>
<TabItem value="pc-same" label="PC (same code)">

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.runWithSource(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}""",
  "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala",
  "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"))
println("```")
```

</TabItem>
<TabItem value="pc-mod" label="PC (modified)">

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.runWithSource(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}""",
  "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala",
  "docExamples/edited/Enrich_modified.scala"))
println("```")
```

</TabItem>
</Tabs>

**Replaces:** Recompiling just to ask a question about half-finished code.
