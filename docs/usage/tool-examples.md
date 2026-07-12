# Tool Examples — Real Output, Self-Verifying

Every tool example on this page is **executed at docs build time** by the real Scala 3.8.4 analyzer. No hand-written JSON; if the tool call fails, the docs build fails. This page cannot rot.

Tools that show the compiler's enriched view of source code (inferred types, resolved implicits, signatures) have moved to their own page: **[Enriching Tools](./enriching-tools.md)**, with a source-vs-enriched diff view.

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
| **Enriching tools** ([separate page](./enriching-tools.md)) | |
| `annotated_source` | Compiler-visible facts and inferred types |
| `method_signature` | Full signature with implicit/using params |
| `document_outline` | File structure with compiler-rendered names |
| `resolve_implicits` | Which givens/implicits apply |
| `trace_implicit_chain` | Path of implicit dependencies |
| `type_at_position` | Type of code at a source location |

## Exploration / edit-plan tools

These tools return precise semantic answers — a symbol, a usage set, a hierarchy, an edit plan — replacing whole-file reads and grep guesswork.

### find_symbol

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("find_symbol"))
```

Grep `transform` returns 5+ matches across comments and strings. `find_symbol` returns 1 definition.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "find_symbol",
  """{"query":"transform"}"""))
```

**Replaces:** Grepping → exact definition lookup.

---

### class_hierarchy

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("class_hierarchy"))
```

```scala mdoc:passthrough
val procSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"Processor"}""")
val procData = ujson.read(procSym)
val procSymbol = if procData("count").num.toInt > 0 then procData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("class_hierarchy", s"""{"symbol":"$procSymbol"}"""))
```

**Replaces:** Reading files + grepping for extends/implements.

---

### find_overloads

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("find_overloads"))
```

```scala mdoc:passthrough
val fmtSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"format"}""")
val fmtData = ujson.read(fmtSym)
val fmtSymbol = if fmtData("count").num.toInt > 0 then fmtData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("find_overloads", s"""{"symbol":"$fmtSymbol"}"""))
```

**Replaces:** Reading code for all overloads.

---

### find_usages

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("find_usages"))
```

```scala mdoc:passthrough
val useSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"transform"}""")
val useData = ujson.read(useSym)
val useSymbol = if useData("count").num.toInt > 0 then useData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("find_usages", s"""{"symbol":"$useSymbol"}"""))
```

**Replaces:** Grepping all files → exact reference list.

---

### members

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("members"))
```

```scala mdoc:passthrough
val memSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"UpperProcessor"}""")
val memData = ujson.read(memSym)
val memSymbol = if memData("count").num.toInt > 0 then memData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("members", s"""{"symbol":"$memSymbol"}"""))
```

**Replaces:** Reading class + all superclass definitions.

---

### call_path

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("call_path"))
```

`pipeline` never calls `process` directly, but reaches it through `compose` and `transform`. The tool returns the shortest path and the call-site of every edge.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "call_path",
  """{"from":"com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline().","to":"com/github/mercurievv/scalasemantic/docexamples/Processor#process().","detailed":true}"""))
```

**Replaces:** Manually reading through call sites to prove reachability.

---

### method_call_hierarchy

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("method_call_hierarchy"))
```

Outgoing from `pipeline`: `compose`, then the two `transform` calls, then `process` — the whole fan-out in one call.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "method_call_hierarchy",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Navigate$package.pipeline().","direction":"callees"}"""))
```

**Replaces:** Opening each callee in turn to build the tree by hand.

---

### value_flow

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("value_flow"))
```

The `input` parameter of `pipeline` flows into `compose`'s `input`, then `transform`'s `input`, then `process`'s `x` — a rename at every hop that text search cannot follow.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "value_flow",
  """{"file":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Navigate.scala","line":19,"column":13}"""))
```

**Replaces:** Manually chasing a value through renamed parameters across files.

---

### rename_plan

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("rename_plan"))
```

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

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("move_plan"))
```

```scala mdoc:passthrough
val movSym = scalasemantic.docs.ToolRunner.run("find_symbol", """{"query":"calculateTotal"}""")
val movData = ujson.read(movSym)
val movSymbol = if movData("count").num.toInt > 0 then movData("symbols")(0)("symbol").str else "unknown"
println(scalasemantic.docs.ToolRunner.runPretty("move_plan", s"""{"symbol":"$movSymbol","newOwner":"com/example/math/"}"""))
```

**Replaces:** Manual refactoring and import management.

---

### extract_method_plan

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("extract_method_plan"))
```

The tool analyzes the range, identifies local variables and scope, returns exact edits.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "extract_method_plan",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Refactor.scala","startLine":5,"startCharacter":20,"endLine":8,"endCharacter":9}"""))
```

**Replaces:** Manual method extraction and variable management.

---

### structure

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("structure"))
```

A snapshot of entire dependency structure in one call.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty("structure", "{}"))
```

**Replaces:** Manual dependency graph construction.

---

### smart_code_duplications

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("smart_code_duplications"))
```

The tool finds structural duplicates (same pattern, different names), ignoring syntactic noise.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "smart_code_duplications",
  """{"minSize":15}"""))
```

**Replaces:** Manual code review for duplication.

---

## Enriching tools

Moved to their own page: **[Enriching Tools](./enriching-tools.md)** — covers `annotated_source`, `method_signature`, `document_outline`, `type_at_position`, `resolve_implicits`, `trace_implicit_chain`, and the presentation-compiler tools on modified/dirty buffers, with a source-vs-enriched diff view.
