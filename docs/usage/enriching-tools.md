# Enriching Tools — Compiler View vs. Source Text

These tools show the LLM what the compiler sees but the source text does not — inferred types, synthesized implicit arguments and conversions, resolved signatures. Every example below runs against the same source file, executed at docs build time by the real Scala 3.8.4 analyzer.

## Source under analysis

`Enrich.scala` — a small typeclass (`Show`), two `given` instances, and two calls to a generic `render` method that hides its resolved implicit argument. Read straight from the fixture file the tool calls below actually analyze, so this block can never drift from what's shown as "Original" further down.

```scala mdoc:passthrough
val enrichPath = "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"
println(s"```scala\n${scalasemantic.docs.ToolRunner.readSource(enrichPath)}\n```")
```

None of the `(using ...)` arguments or inferred return types above are written in the source — the tools below make them visible.

## annotated_source

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("annotated_source"))
```

The compiler injects `(using intShow)` and `(using listShow(...))` into the `render` calls, and infers the return type `: String` on the `out` and `num` bindings — none visible in source text.

```scala mdoc:passthrough
val annotatedArgs =
  s"""{"uri":"$enrichPath","format":"annotated","annotationsOnly":true}"""
val annotatedRaw = scalasemantic.docs.ToolRunner.run("annotated_source", annotatedArgs)
println(scalasemantic.docs.ToolRunner.requestMarkdown("annotated_source", annotatedArgs))
```

<div className="row">
<div className="col col--6">

**Original**

```scala mdoc:passthrough
println(s"```scala\n${scalasemantic.docs.ToolRunner.readSource(enrichPath)}\n```")
```

</div>
<div className="col col--6">

**Enriched (compiler view)**

```scala mdoc:passthrough
println(s"```scala\n${scalasemantic.docs.ToolRunner.extractField(annotatedRaw, "source")}\n```")
```

</div>
</div>

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.detailsMarkdown(annotatedRaw, Seq("source")))
```

**Replaces:** Reading 15 lines of source → 10 lines with compiler-visible facts.

---

## method_signature

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("method_signature"))
```

The `render` calls in the source read `render(List(1, 2, 3))` — the `Show` instance is invisible there. The signature makes the whole contract explicit.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""))
```

**Replaces:** Reading the definition and hand-tracing the implicit list → one resolved signature.

---

## document_outline

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("document_outline"))
```

The tool returns a tree with compiler-rendered names instead of a text scan. For a 50-line file, the outline is 5–10 lines; for 1000 lines, still manageable.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty("document_outline", s"""{"uri":"$enrichPath"}"""))
```

**Replaces:** Scanning files → structured outline.

---

## type_at_position

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("type_at_position"))
```

No inference needed by hand; the tool returns the exact type the compiler assigned. For complex generics and implicit resolution, invaluable.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "type_at_position", s"""{"uri":"$enrichPath","line":14,"character":6}"""))
```

**Replaces:** Hand type inference → compiler's answer.

---

## resolve_implicits

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("resolve_implicits"))
```

For `Show[_]`, two givens qualify: `intShow` directly and `listShow` (itself parameterized on another `Show`).

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "resolve_implicits",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
```

**Replaces:** Guessing which given applies → the compiler's candidate set.

---

## trace_implicit_chain

```scala mdoc:passthrough
println("> **Answers:** " + scalasemantic.docs.ToolRunner.describe("trace_implicit_chain"))
```

`listShow` produces `Show[List[A]]` only by depending on a `Show[A]`; the chain makes that dependency explicit.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "trace_implicit_chain",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
```

**Replaces:** Manually following each given's own implicit needs → the whole chain.

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

`method_signature` runs on the same `render` symbol, with the same arguments, three ways: against the committed index, against the unmodified file through the presentation compiler (proving the two agree), and against the edited buffer — which reports the new parameter **without any recompile**.

```scala mdoc:passthrough
val modSigArgs =
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""
println(scalasemantic.docs.ToolRunner.requestMarkdown("method_signature", modSigArgs))
```

### DB (committed)

```scala mdoc:passthrough
val dbRaw = scalasemantic.docs.ToolRunner.run("method_signature", modSigArgs)
println(s"```scala\n${scalasemantic.docs.ToolRunner.extractField(dbRaw, "signature")}\n```")
```

### PC (same code)

```scala mdoc:passthrough
val pcSameRaw = scalasemantic.docs.ToolRunner.runWithSource(
  "method_signature", modSigArgs, enrichPath, enrichPath)
println(s"```scala\n${scalasemantic.docs.ToolRunner.extractField(pcSameRaw, "signature")}\n```")
```

### PC (modified)

```scala mdoc:passthrough
val pcModRaw = scalasemantic.docs.ToolRunner.runWithSource(
  "method_signature", modSigArgs, enrichPath, "docExamples/edited/Enrich_modified.scala")
println(s"```scala\n${scalasemantic.docs.ToolRunner.extractField(pcModRaw, "signature")}\n```")
```

**Replaces:** Recompiling just to ask a question about half-finished code.
