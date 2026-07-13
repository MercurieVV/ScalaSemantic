# Implementation plan — annotated_source enrichment axes

Executable plan for the design in
[`docs/explanation/annotated-source-enrichment.md`](explanation/annotated-source-enrichment.md).
**Written for a weak/cheap model (e.g. Haiku): every task is self-contained with exact file paths,
line anchors, copy-paste code, and verification commands.** Do the tasks IN ORDER. Each task is one
PR, independently shippable and golden-locked. Do not start a task until the previous one's
"Acceptance" checks pass.

---

## Rules that apply to EVERY task (read once)

1. **Reading `.scala` files**: to understand a Scala file, view it with the `scala-semantic`
   `annotated_source` MCP tool (not `cat`/`grep`). To EDIT, use the `Edit` tool with the exact
   old→new strings this plan gives you.
2. **Do not invent scope.** Change only what the task says. No refactors, no renames, no reformat of
   untouched code.
3. **Formatting**: after editing, do not hand-format. The pre-push hook runs scalafmt. If a task
   says a line is too long, keep it readable; scalafmt fixes wrapping.
4. **How goldens work** (critical): golden files are LOCKED. When a golden file EXISTS, the test
   FAILS on any drift (it does not auto-update). When the golden file is ABSENT, the test WRITES it
   and passes. **So to regenerate a golden: delete the golden file, run the test once (it writes the
   new file), run it again (it passes).** Always eyeball the newly written golden before committing —
   it is now the source of truth.
5. **Build/test commands** (run from repo root):
   - Compile: `./mill mcp.compile`
   - Run the enrichment golden suite only:
     `./mill mcp.test com.github.mercurievv.scalasemantic.mcp.DocsEnrichingExamplesGoldenSuite`
   - Run all mcp tests: `./mill mcp.test`
   Pipe noisy output through `| tail -30` or `| grep -iE 'error|fail'`.
6. **Commit/merge**: use `./tree2m <branch> "<conventional commit message>"`. Do NOT pre-run
   mill checks — the pre-push hook does. Pick a `feat(...)`-style message.
7. **Key files you will touch** (memorize these anchors):
   - Enum: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/InputTypes.scala:288`
     (`enum SourceFormat`).
   - Renderer + params: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala`
     — `object SourceView` at line 96; `noteText` at 168; `preciseColKinds` at 163; `legend` at 171;
     `SourceView.params` at 99; `SourceView.result` at 117; `render` at 133; the tool definition +
     description at 963–1001; arg helpers `argBool` 262 / `argStr` 259 / `argFormat` 340.
   - Annotation model: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/Models.scala:224`
     (`case class SourceAnnotation`).
   - Analyzer entry: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/Analyzer.scala:183`
     (`sourceAnnotations`); helpers in `AnalyzerHelpers.scala` (`syntheticAnnotation` 43,
     `insertedName` 78, `packageDotted` 137, `joinFqn` 148).
   - Golden test: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/DocsEnrichingExamplesGoldenSuite.scala`.
   - Golden files: `mcp/src/test/resources/docs-golden/annotated_source_enrich.scala` (+ `.json`).

---

## Task 1 — `terse`: retire `col N`, anchor notes to the token

**Goal.** Positional notes currently read `col 18 [Int]` (a column number the reader must count).
Replace the column number with the identifier at that column, so a note reads `List[Int]`. No new
parameter yet — this becomes the default rendering.

**Why safe.** Every `SourceAnnotation` already has `line` + `character`; `render` already has the
source line text (`src`). We just look up the word at `character`.

### Steps

**1.1** In `McpTools.scala`, add a helper inside `object SourceView` (put it right above `noteText`,
around line 165):

```scala
/** The identifier at 0-based `col` in `src` (letters/digits/`_`/`$`), or "" if none. */
private def identifierAt(src: String, col: Int): String =
  if col < 0 || col >= src.length then ""
  else
    def isIdChar(c: Char) = c.isLetterOrDigit || c == '_' || c == '$'
    if !isIdChar(src.charAt(col)) then ""
    else
      var end = col
      while end < src.length && isIdChar(src.charAt(end)) do end += 1
      src.substring(col, end)
```

**1.2** Replace `noteText` (currently at 168–169) so it takes the source line and anchors to the
token instead of emitting `col N`:

```scala
/** An annotation's display text. Positional kinds are anchored to the identifier at their column
  * (e.g. `render[List[Int]]`, `List.apply(…)`) instead of a `col N` prefix. */
private def noteText(src: String, n: SourceAnnotation): String =
  if !preciseColKinds.contains(n.kind) then n.text
  else
    val tok = identifierAt(src, n.character)
    if tok.isEmpty then n.text
    else if n.kind == "inferred-type-args" then s"$tok${n.text}" // n.text starts with '['
    else s"$tok.${n.text}"                                        // implicit-conversion: apply(…)
```

**1.3** Update the ONE caller inside `render` (line 151):

```scala
val joined = notes.map(noteText).mkString("; ")
```
becomes
```scala
val joined = notes.map(n => noteText(src, n)).mkString("; ")
```

**1.4** Update the `legend` text (lines 171–184): delete the `` `col N` (1-based) pins the call a
note applies to.`` clause from the shared `markers` string; positional notes are now self-evident.

**1.5** Update the tool DESCRIPTION (McpTools.scala ~970): remove the phrase
`` (with `col N`, 1-based, when it pins a precise spot) `` — replace with
`` (anchored to the identifier it applies to) ``.

### Regenerate golden & verify
```
rm mcp/src/test/resources/docs-golden/annotated_source_enrich.scala
./mill mcp.test com.github.mercurievv.scalasemantic.mcp.DocsEnrichingExamplesGoldenSuite   # writes new .scala
./mill mcp.test com.github.mercurievv.scalasemantic.mcp.DocsEnrichingExamplesGoldenSuite   # passes
```
Then view the regenerated file via `annotated_source` (or open it) and confirm.

### Acceptance
- New `annotated_source_enrich.scala` contains **no** substring `col ` anywhere.
- Line for `val out = render(List(1, 2, 3))` now reads something like
  `// ⟹ : String; (using listShow); render[List[Int]]; List.apply(…); List[Int]; (using intShow)`.
- `./mill mcp.test` passes (whole suite). Commit with `tree2m`, message
  `feat(annotated-source): anchor enrichment notes to tokens, retire col N`.

### Guardrails
- Do NOT try to merge `List.apply(…)` and `List[Int]` into `List.apply[Int]` — that is Task 4's job.
  Keep one note per synthetic.

---

## Task 2 — `symbols`: append a resolved-FQN legend

**Goal.** New param `symbols` (`off` default | `on`). When `on`, append a compact map of the
distinct types used in the file to their dotted fully-qualified names, skipping well-known predefs.

### Steps

**2.1** Add an analyzer method. In `AnalyzerHelpers.scala` (near `packageDotted`, line 137) add:

```scala
/** Dotted FQN of a type symbol: `scala/collection/immutable/List#` -> `scala.collection.immutable.List`. */
@pure
def typeSymbolFqn(sym: String): String =
  sym.stripSuffix("#").replace('/', '.').replace('#', '.')
```

**2.2** In `Analyzer.scala` (near `sourceAnnotations`, line 183) add:

```scala
/** Distinct type symbols referenced in `uri`, as (simpleName -> dotted FQN), sorted, skipping
  * `scala.*` / `java.lang.*` (universally known). Empty if `uri` is not indexed. */
def symbolLegend(uri: DocumentUri): List[(String, String)] =
  index.document(uri.value).toList.flatMap { doc =>
    doc.occurrences.iterator
      .map(_.symbol)
      .filter(_.endsWith("#"))                                   // type symbols only
      .filterNot(s => s.startsWith("scala/") || s.startsWith("java/lang/"))
      .distinct
      .map(s => index.displayName(s) -> h.typeSymbolFqn(s))
      .filter(_._1.nonEmpty)
      .toList
      .distinct
      .sortBy(_._1)
  }
```
(`h` is the `AnalyzerHelpers` instance already used in `sourceAnnotations`.)

**2.3** Thread it through `SourceView.result`. Add a parameter `symbols: List[(String, String)]`
(default `Nil`) after `annotationsOnly`, and when non-empty append lines to the rendered `source`.
In `SourceView.result` (line 117) after computing the rendered source, if `symbols.nonEmpty` append:
```
// symbols:
//   List → scala.collection.immutable.List
```
Concretely, build a suffix string:
```scala
val legendBlock =
  if symbols.isEmpty then ""
  else symbols.map((n, fqn) => s"//   $n → $fqn").mkString("\n// symbols:\n", "\n", "")
```
and append it to the `render(...)` result before wrapping in the ujson object. (Single entry may be
inlined as `// symbols: List → …`; multi-entry uses the block above — either is fine, the golden
captures it.)

**2.4** Add the schema entry. In `SourceView.params` (line 99) append:
```scala
("symbols", "boolean", "append a legend mapping each type used to its full package path (default false)")
```

**2.5** In the tool handler (McpTools.scala ~984–999) read the flag and pass the legend:
```scala
val symbols = if argBool(a, "symbols", false) then az.symbolLegend(uri) else Nil
```
and pass `symbols` as the new `SourceView.result` argument.

**2.6** Update `legend(fmt)` strings to mention: `symbols=on adds a type→package legend`.

### New golden
Add a test case in `DocsEnrichingExamplesGoldenSuite.scala` (copy the `annotated_source` test at
line 77) named `annotated_source_enrich_symbols` passing `"symbols" -> true`. Run the suite twice
(first run writes `annotated_source_enrich_symbols.json` + `.scala`).

### Acceptance
- With `symbols=false` the output is byte-identical to Task 1 (no legend). The existing golden must
  still pass unchanged.
- New golden shows `// symbols:` with `List → scala.collection.immutable.List` and does NOT list
  `Int`, `String` (predef-skipped). Commit: `feat(annotated-source): add symbols=on FQN legend`.

---

## Task 3 — `docs`: strip comments to save tokens

**Goal.** New param `docs` (`keep` default | `strip`). When `strip`, remove `//` line comments and
`/* … */` (incl `/** */`) block comments from the source BEFORE weaving notes. Do not delete lines
(keep line indices stable so annotations still line up) — just blank the comment characters.

### Steps

**3.1** Add a pure comment-stripper. Put it in `AnalyzerHelpers.scala` or a small util; give it its
own MUnit test (this is the error-prone part — test it). Full function to paste:

```scala
/** Blank out `//` and `/* */` comments across `lines`, preserving line count and string literals.
  * Returns lines with comment characters replaced by spaces (so column offsets stay valid). */
def stripComments(lines: IndexedSeq[String]): IndexedSeq[String] =
  val text = lines.mkString("\n")
  val out = new StringBuilder(text.length)
  var i = 0
  var inString = false
  var inChar = false
  var block = 0
  while i < text.length do
    val c = text.charAt(i)
    val d = if i + 1 < text.length then text.charAt(i + 1) else ' '
    if block > 0 then
      if c == '*' && d == '/' then { out.append("  "); i += 2; block -= 1 }
      else { out.append(if c == '\n' then '\n' else ' '); i += 1 }
    else if inString then
      out.append(c); if c == '\\' then { out.append(d); i += 2 } else { if c == '"' then inString = false; i += 1 }
    else if inChar then
      out.append(c); if c == '\\' then { out.append(d); i += 2 } else { if c == '\'' then inChar = false; i += 1 }
    else if c == '"' then { inString = true; out.append(c); i += 1 }
    else if c == '\'' then { inChar = true; out.append(c); i += 1 }
    else if c == '/' && d == '/' then
      while i < text.length && text.charAt(i) != '\n' do { out.append(' '); i += 1 }
    else if c == '/' && d == '*' then { block += 1; out.append("  "); i += 2 }
    else { out.append(c); i += 1 }
  out.toString.split("\n", -1).toIndexedSeq
```
Note: this uses local `var`s — Wartremover forbids `var` in normal code. Mark the method
`@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))` OR (preferred)
rewrite as a tail-recursive scan. If unsure, use the `@SuppressWarnings` escape and leave a
`// TODO: rewrite functionally` — a follow-up can purify it. Add a unit test covering: a `//` after
code, a `/** */` doc block, and a `//` INSIDE a string literal (must be preserved).

**3.2** Wire it in the handler: read `argStr(a, "docs")`; when it equals `"strip"`, replace `lines`
with `az.stripComments(lines)` (expose `stripComments` via a thin `Analyzer` method) BEFORE calling
`az.sourceAnnotations` and `SourceView.result`.

**3.3** Add schema entry to `SourceView.params`:
```scala
("docs", "string", "keep (default) | strip — drop comments for a leaner token view")
```

### New golden
Add test case `annotated_source_enrich_docs` with `"docs" -> "strip"`. Note: `Enrich.scala` has no
comments, so to make the golden meaningful, add a fixture with a doc comment OR just assert the
stripper's unit test (the golden mainly proves the flag is wired). Prefer the unit test for the
stripper + one golden showing the flag passes through.

### Acceptance
- Stripper unit test passes, including the "`//` inside a string is preserved" case.
- `docs=keep` output identical to before. Commit: `feat(annotated-source): add docs=strip`.

---

## Task 4 — `full`: elaborated expression (do the SPIKE first)

**Highest risk. Corpus caveats are real** (see the design doc §5). Split into 4a (spike, no product
code) then 4b (implement) — get 4a reviewed before 4b.

### 4a — Spike (throwaway script, ~1 hour)
Reproduce and study the synthetics. A working script already exists — reuse this shape
(`scala-cli run`):

```scala
//> using dep org.scalameta:semanticdb-shared_2.13:4.13.10
import scala.meta.internal.{semanticdb => s}
import java.nio.file.{Files, Paths}
val f = Paths.get(sys.env("SDB"))
for d <- s.TextDocuments.parseFrom(Files.readAllBytes(f)).documents; syn <- d.synthetics do
  val r = syn.range.getOrElse(s.Range.defaultInstance)
  println(s"@${r.startLine + 1}:${r.startCharacter + 1}  ${syn.tree}")
```
Run with `SDB=out/docExamples/compile.dest/classes/META-INF/semanticdb/docExamples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala.semanticdb`.

Confirm (already observed once — reconfirm on your machine):
- The using-arg synthetic nests the inner given: `ApplyTree(OriginalTree(render), [ApplyTree(IdTree(listShow), [IdTree(intShow)])])`.
- A call's type-app and using-app are SEPARATE synthetics at the same range → must merge by range.
- Duplicate synthetics exist (e.g. `List.apply[Int]` twice; `intShow` twice) → must dedup by range.
- Some type-args are NOT materialized (`listShow[Int]`) → elaboration will be incomplete; accept it.

Write findings as a comment in the PR. **Do not proceed to 4b if the nesting is not present** — ask
for guidance instead.

### 4b — Implement
1. Add param `detail` (`terse` default | `full`) — mirror the `SourceFormat` enum pattern at
   `InputTypes.scala:288`: new `enum SourceDetail(val value)` with `Terse`/`Full` + a `from` that
   defaults to `Terse`. Add an `argDetail` helper next to `argFormat` (McpTools.scala:340).
2. In `AnalyzerHelpers`, add a tree-printer `renderTree(tree: s.Tree, src): String` that walks
   `ApplyTree` (function + `(using args)`), `TypeApplyTree` (`base[typeArgs]`), `SelectTree`
   (`recv.id`), `IdTree` (display name of symbol), and `OriginalTree` (the source substring for its
   range). Reuse `renderType` for type args and `index.displayName` for symbols.
3. Add `fullAnnotations(uri, lines)`: group `doc.synthetics` by range, pick the richest tree per
   range (prefer the one whose function is an `ApplyTree`/`TypeApplyTree`, i.e. most nested),
   dedup, and emit ONE `SourceAnnotation` per call site whose `text` is `renderTree(...)`.
4. In `Analyzer.sourceAnnotations`, branch on `detail`: `Terse` → current path; `Full` →
   `fullAnnotations`. Thread `detail` from the handler through to here (add a parameter).
5. Add golden `annotated_source_enrich_full` with `"detail" -> "full"`.

### Acceptance
- `detail=terse` output identical to Task 1. `detail=full` golden shows the merged nested form for
  line 15, e.g. `render[List[Int]](List.apply[Int](1, 2, 3))(using listShow(using intShow)): String`
  (the `listShow[Int]` type-arg may be absent — that is the documented corpus limit).
- Commit: `feat(annotated-source): add detail=full elaborated expression mode`.

---

## Done-when
All four tasks merged; `./mill mcp.test` green; five annotated_source goldens exist (default, terse
is default, symbols, docs, full); the design doc §7 sequencing is fully checked off. `col N` appears
nowhere in output.
