# Enriching Tools — Compiler View vs. Source Text

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

These tools show the LLM what the compiler sees but the source text does not — inferred types, synthesized implicit arguments and conversions, resolved signatures. Every example below runs against the same source file, executed at docs build time by the real Scala 3.8.4 analyzer.

## Source under analysis

`Enrich.scala` — a small typeclass (`Show`), two `given` instances, and two calls to a generic `render` method that hides its resolved implicit argument:

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

None of the `(using ...)` arguments or inferred return types above are written in the source — the tools below make them visible.

## annotated_source

> **Answers:** The compiler's exact view of the file: inferred types on every binding, synthesized (using) arguments, implicit conversions.

The compiler injects `(using intShow)` and `(using listShow(...))` into the `render` calls, and infers the return type `: String` on the `out` and `num` bindings — none visible in source text. Side by side:

<div className="row">
<div className="col col--6">

**Original**

```scala
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

</div>
<div className="col col--6">

**Enriched (compiler view)**

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "annotated_source",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala","format":"annotated","annotationsOnly":true}"""))
```

</div>
</div>

**Replaces:** Reading 15 lines of source → 10 lines with compiler-visible facts.

---

## method_signature

> **Answers:** The full resolved signature of a method — including the `(using ...)` / implicit parameter lists that are written once at the definition and never at the call sites.

The `render` calls in the source read `render(List(1, 2, 3))` — the `Show` instance is invisible there. The signature makes the whole contract explicit.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""))
```

**Replaces:** Reading the definition and hand-tracing the implicit list → one resolved signature.

---

## document_outline

> **Answers:** File outline with compiler-rendered signatures (compiler names, not source text).

The tool returns a tree with compiler-rendered names instead of a text scan. For a 50-line file, the outline is 5–10 lines; for 1000 lines, still manageable.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "document_outline",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"}"""))
```

**Replaces:** Scanning files → structured outline.

---

## type_at_position

> **Answers:** The inferred type at a specific source location.

No inference needed by hand; the tool returns the exact type the compiler assigned. For complex generics and implicit resolution, invaluable.

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "type_at_position",
  """{"uri":"docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala","line":14,"character":6}"""))
```

**Replaces:** Hand type inference → compiler's answer.

---

## resolve_implicits

> **Answers:** Which given/implicit definitions can produce a wanted type — the search the compiler does at every implicit parameter, which text search cannot do.

For `Show[_]`, two givens qualify: `intShow` directly and `listShow` (itself parameterized on another `Show`).

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "resolve_implicits",
  """{"type":"com/github/mercurievv/scalasemantic/docexamples/Show#"}"""))
```

**Replaces:** Guessing which given applies → the compiler's candidate set.

---

## trace_implicit_chain

> **Answers:** The givens that produce a type **and the implicits they transitively pull in** — implicit resolution followed step by step.

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

The three tabs run `method_signature` on the same `render` symbol: against the committed index, against the unmodified file through the presentation compiler (proving the two agree), and against the edited buffer — which reports the new parameter **without any recompile**.

<Tabs>
<TabItem value="db" label="DB (committed)" default>

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runPretty(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}"""))
```

</TabItem>
<TabItem value="pc-same" label="PC (same code)">

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runWithSourcePretty(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}""",
  "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala",
  "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala"))
```

</TabItem>
<TabItem value="pc-mod" label="PC (modified)">

```scala mdoc:passthrough
println(scalasemantic.docs.ToolRunner.runWithSourcePretty(
  "method_signature",
  """{"symbol":"com/github/mercurievv/scalasemantic/docexamples/Enrich$package.render()."}""",
  "docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala",
  "docExamples/edited/Enrich_modified.scala"))
```

</TabItem>
</Tabs>

**Replaces:** Recompiling just to ask a question about half-finished code.
