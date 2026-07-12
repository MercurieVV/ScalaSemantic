# Plan: mdoc-driven "tool examples" pages (real, self-verifying)

## Goal

Build documentation pages that, for each ScalaSemantic MCP tool, show:

1. a small, realistic **input** Scala snippet,
2. the **exact JSON** an LLM receives when that tool runs on it,

so readers see, on real code, **how each tool enriches code for an LLM** (adds compiler-invisible
facts) and **what it cuts** (noise/whole-file reads it replaces). Every shown output MUST be produced
by really running the tool during the docs build — never hand-written — so the page cannot rot.

Hard requirement from the maintainer: the `scala` fences are **executed by mdoc** at build time.
If a fence is not really run, the output is not trustworthy.

---

## The core constraint (read first — dictates the whole design)

mdoc compiles each `scala mdoc` fence **at build time using the Scala 3 compiler bundled with the
`mdoc` artifact**. `org.scalameta::mdoc:2.9.0` is built on the **Scala 3.3 LTS line** (its POM pulls
`scala3-library_3:3.3.7`); there is **no mdoc published on the 3.8 line**
(`mdoc-parser_3.8.4` → HTTP 404). The ScalaSemantic analyzer (`core`/`analysis`/`mcp`) is compiled
by **Scala 3.8.4**.

Therefore a fence **cannot directly call** `Analyzer`/`McpTools`: that would force mdoc's 3.3.7
compiler to read 3.8.4 TASTy, which dotty refuses (TASTy is forward-incompatible — an older compiler
rejects newer TASTy). This is exactly why the existing `mdoc-docs/DocsMain.scala` comment calls the
snippets "illustrative rather than in-process analyzer calls".

### The way through (chosen approach)

Keep the fence **really executed by mdoc**, but have it invoke the real 3.8.4 tools **out-of-process**
through a small CLI jar:

```
scala mdoc fence (compiled+run by mdoc's 3.3.7 compiler)
   │  uses only os-lib + upickle (both publish for 3.3 — verified)
   ▼
os.proc(java, -jar, tool-cli.jar, --index <dir> --tool <name> --args <json>)
   │  child JVM = the REAL Scala 3.8.4 assembly
   ▼
SemanticIndex → Analyzer → McpTools.all → tool.run(args)  ← production code path
   ▼
prints the exact tool JSON on stdout  →  captured by the fence  →  rendered by mdoc
```

Why this satisfies "always show real functionality":

- The fence **is** executed by mdoc (not a copied string). If the tool call fails, the docs build
  fails — the page cannot drift from reality.
- The child process is the **actual production `mcp` assembly** (Scala 3.8.4), so the JSON is exactly
  what a real MCP client gets — not a 3.3-recompiled approximation.

### Rejected alternatives (record why, don't reopen)

- **Direct fence calls to Analyzer** — blocked by the TASTy wall above.
- **Cross-compile `core`+`analysis`+`mcp` to Scala 3.3 LTS** so fences call them directly. Large build
  effort (scalameta/wartremover/pc pins), and — worse — the 3.3-compiled analyzer would read 3.3-emitted
  SemanticDB, so the docs could show **different** behavior than the shipped 3.8.4 tool. Less faithful,
  not more. Do not do this.
- **Hand-written JSON in `scala mdoc:passthrough`** — not executed, rots. This is the current problem.

---

## Architecture / new pieces

Three new build pieces + the doc pages.

```
doc-examples/            NEW Mill module — the input fixtures (compiled with SemanticDB)
  src/main/scala/com/github/mercurievv/scalasemantic/docexamples/*.scala

mcp  (existing module)   NEW runnable object `ToolCli` (Scala 3.8.4)  → mcp.assembly() jar
mdoc-docs (existing)     NEW helper `ToolRunner` (Scala 3.3) that shells to the jar
                         + os-lib/upickle deps; DocsMain passes jar+index paths as site vars

docs/usage/tool-examples.md      NEW single page, three H2 sections:
                                   ## Enriching tools
                                   ## Exploration / edit-plan tools
                                   ## Tools on modified code (Docusaurus tabs)
```

---

## Task list (each task is bounded; do them in order)

### T1 — `doc-examples` fixture module

Create a Mill module holding the **input** snippets. Keep each fixture tiny (5–20 lines), idiomatic
Scala, and chosen so the target tool produces a visibly interesting answer.

1. Add to `build.mill`, mirroring the existing `Common`/`SbtModule` pattern used by `core`/`analysis`
   (so it emits `*.semanticdb`). It does NOT need wartremover strictness — set `useWartremover = false`
   to avoid fighting example code. It has no dependency on the app modules.

   ```scala
   object docExamples extends Common {
     def id = "doc-examples"
     override def useWartremover = false
     // strict -Werror/-Wunused will reject illustrative unused code; relax for fixtures:
     override def scalacOptions =
       super.scalacOptions().filterNot(o => o == "-Werror" || o.startsWith("-Wunused"))
   }
   ```
   (Confirm the exact `Common` knobs by reading `build.mill` lines ~40–140 before editing. The goal:
   this module compiles and writes SemanticDB under `out/docExamples/compile.dest/classes/META-INF/semanticdb/**`,
   same as every other module.)

2. Author the fixture sources under
   `doc-examples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/`.
   Suggested files (one theme each — see the example table in T5/T6 for what each must contain):
   - `Enrich.scala`   — implicits/given, inferred types, implicit conversions (feeds annotated_source,
     method_signature, resolve_implicits, trace_implicit_chain, type_at_position, document_outline).
   - `Navigate.scala` — a small class hierarchy, overloaded methods, a call chain a→b→c, a val whose
     value flows through calls (feeds find_symbol, find_usages, class_hierarchy, find_overloads,
     members, call_path, method_call_hierarchy, value_flow).
   - `Refactor.scala` — a top-level def/class with several usages, plus a method with an extractable
     range (feeds rename_plan, move_plan, extract_method_plan).
   - `Duplication.scala` — two structurally-identical blocks (feeds smart_code_duplications).

3. Verify: `./mill docExamples.compile` succeeds and SemanticDB files appear.

### T2 — `ToolCli`: a thin, real tool runner in the `mcp` module (Scala 3.8.4)

Add `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/ToolCli.scala`. It is the ONLY new
production code; it just wires existing pieces so the assembly jar can run any tool once and print the
exact JSON.

```scala
package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import java.nio.file.Paths

/** One-shot tool runner used by the docs build. Loads the SemanticDB index at `--index`, runs the
  * named tool with the given JSON args, prints the exact tool-result JSON (pretty) to stdout.
  *
  *   java -cp <mcp-assembly> com.github.mercurievv.scalasemantic.mcp.ToolCli \
  *     --index out/docExamples/compile.dest/classes --tool find_usages --args '{"symbol":"..."}'
  *
  * `--root` (default ".") is the project root the tool resolves file uris against.
  */
object ToolCli:
  def main(args: Array[String]): Unit =
    val m = parse(args) // tiny --k v parser; keep it dependency-free
    val indexDir = m("index")
    val toolName = m("tool")
    val argsJson = ujson.read(m.getOrElse("args", "{}"))
    val root = Paths.get(m.getOrElse("root", "."))
    val index = SemanticIndex.fromRoots(Seq(Paths.get(indexDir)))
    val tools = McpTools.all(Analyzer(index), root)
    val tool = tools.find(_.name == toolName)
      .getOrElse(sys.error(s"unknown tool: $toolName (have: ${tools.map(_.name).mkString(",")})"))
    println(ujson.write(tool.run(argsJson), indent = 2))
```

Notes:
- Use `SemanticIndex.fromRoots` (core, line ~93) — points at exactly the doc-examples output, so the
  index contains ONLY the fixtures (clean, small, deterministic outputs). Do NOT use
  `fromProject(".")` here (that would index the whole repo and pollute results).
- `McpTools.all` / `Tool.run` are the exact production path (`mcp/.../McpTools.scala`,
  `Mcp.scala:186` calls `tool.run(args)` the same way). So JSON == what an LLM receives.
- For the modified-code section (T7) `ToolCli` needs a PC-backed Analyzer. Add optional flags
  `--source <path>` `--uri <rel>`: when present, build the analyzer with a presentation-compiler
  backend and pass `source` through the tool args, exactly as `McpPcSuite.scala:56`
  (`PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
  Analyzer(index, Some(backend)) }`) and the tool's own `source`/`uri` args. Read `McpPcSuite` before
  writing this; it is the reference for wiring the PC backend and the classpath it needs.

Build: `mcp.assembly()` already exists (build.mill:583). Confirm
`./mill mcp.assembly` produces a runnable jar and note its path (`out/mcp/assembly.dest/out.jar` or
similar — read the task output).

### T3 — `ToolRunner` helper in `mdoc-docs` (Scala 3.3), callable from fences

`mdoc-docs` is Scala 3.3.4. Add deps so fences can shell out and parse JSON:

```scala
// build.mill, object docs:
def mvnDeps = Seq(
  mvn"org.scalameta::mdoc:2.9.0",
  mvn"com.lihaoyi::os-lib:0.11.3",   // 3.3 build verified present
  mvn"com.lihaoyi::upickle:4.4.3"    // 3.3 build verified present
)
```

Add `mdoc-docs/src/main/scala/ToolRunner.scala`:

```scala
package scalasemantic.docs

/** Runs a real ScalaSemantic tool by shelling to the 3.8.4 assembly jar and returns its exact JSON.
  * Paths come from system properties set by DocsMain (see T4). Used from mdoc fences, so it must
  * depend only on os-lib (3.3-compatible). Any non-zero exit throws → the docs build fails loudly.
  */
object ToolRunner:
  private def jar   = sys.props("scalasemantic.docs.toolCliJar")
  private def index = sys.props("scalasemantic.docs.indexDir")
  private def root  = sys.props.getOrElse("scalasemantic.docs.root", ".")

  def run(tool: String, args: String): String =
    os.proc("java", "-cp", jar, "com.github.mercurievv.scalasemantic.mcp.ToolCli",
            "--index", index, "--root", root, "--tool", tool, "--args", args)
      .call(check = true).out.text().trim

  /** Modified-buffer variant (T7): passes the edited file text + its uri. */
  def runWithSource(tool: String, args: String, uri: String, sourcePath: String): String =
    os.proc("java", "-cp", jar, "com.github.mercurievv.scalasemantic.mcp.ToolCli",
            "--index", index, "--root", root, "--tool", tool, "--args", args,
            "--uri", uri, "--source", sourcePath)
      .call(check = true).out.text().trim
```

### T4 — Wire paths through `DocsMain`

Extend `mdoc-docs/src/main/scala/DocsMain.scala` so `ToolRunner`'s system properties are set before
mdoc runs, and add the fixture-source directory as an mdoc site variable so pages can show input code
by include (optional; see T5). Keep the existing `@VERSION@` wiring.

```scala
System.setProperty("scalasemantic.docs.toolCliJar", <path to mcp assembly jar>)
System.setProperty("scalasemantic.docs.indexDir",   <path to doc-examples classes dir>)
System.setProperty("scalasemantic.docs.root",       ".")
```

Pass those two paths **into** the docs run from Mill (they are build-time locations). Update
`object docs` `forkArgs` to append them, e.g.:

```scala
def forkArgs = Task {
  Seq(
    s"-Dscalasemantic.docs.version=${latestReleaseVersion()}",
    s"-Dscalasemantic.docs.toolCliJar=${mcp.assembly().path}",
    s"-Dscalasemantic.docs.indexDir=${docExamples.compile().classes.path}"
  )
}
```
(Read Mill's `assembly`/`compile` task result types to get the exact `.path`/`.classes` accessors;
adjust names to what compiles. The intent: docs depends on `mcp.assembly()` and `docExamples.compile()`
so both are built before mdoc runs.)

DocsMain then reads them via `sys.props` (already available to `ToolRunner`). Nothing else changes.

### T4.5 — Authoring template & presentation layer (T5–T7 MUST follow this)

Raw tool JSON is optimized for an LLM, not a human reader. A page that just dumps input + a JSON blob
teaches *that* the tool ran, not *what it added and why it matters*. Every example on the page follows
the fixed template below so the "enrich / cut" story is explicit, scannable, and consistent.

**Per-tool template** (identical shape for every tool — reader learns it once):

````markdown
### `tool_name`

> **Answers:** one line — "who calls X", "the resolved signature of Y", … (from `docs/reference/tools.md`).

**Input** (`Fixture.scala`):

```scala
<the small fixture snippet>
```

**Plain read vs the tool** &nbsp;— the contrast block (see rules below).

:::tip What the LLM gains / what it cuts
One sentence naming the exact enrichment (a synthesized `(using …)`, an inferred `: T`) OR the noise
removed ("3 real references, not 14 grep hits"). This is the takeaway; keep it concrete.
:::

**Replaces:** a short on-brand cost line, e.g. *"reading the 210-line file → this 6-line outline"* or
*"~40 tokens vs ~1200 for the raw file"*. Illustrative framing (hand-written, not build-verified).

<details>
<summary>Full tool JSON</summary>

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run("tool_name", """{…real args…}"""))
println("```")
```

</details>
````

**The contrast block — the core teaching device.** It differs by tool family:

- **Enrich tools** (annotated_source, method_signature, document_outline, resolve_implicits,
  trace_implicit_chain, type_at_position): render **plain vs enriched side by side**, so the added
  facts are visible. The "plain" side is build-real too — get it from the same tool, e.g.
  `annotated_source` with `format:"plain"` (raw file, no notes) beside `format:"annotated"`
  (or `annotationsOnly:true`). Both come from `ToolRunner`, so the contrast can't rot. Present as two
  adjacent fenced blocks (or a two-column Docusaurus layout if easy) labelled "Text read sees" vs
  "ScalaSemantic adds".

- **Cut tools** (find_usages, document_outline, members, structure, rename_plan, …): lead with a
  **count contrast** in prose — *"grep `render` → 14 matches across 6 files (comments, a string, an
  unrelated `render`); `find_usages` → 3 real references."* The grep number is hand-written illustration
  (mark it as such); the tool number is the real output. This makes the token/noise saving concrete and
  is on-brand for the project's whole premise.

**Docusaurus admonitions & collapsibles:** the `:::tip` block and the `<details>` raw-JSON collapsible
are Docusaurus MDX built-ins — no imports needed (only `<Tabs>` in T7 needs imports). Leading with the
plain-English takeaway and hiding the full JSON behind `<details>` keeps the page readable while
preserving the exact-JSON fidelity for anyone who expands it.

**Top-of-page summary table (quick-reference).** Immediately after the page intro, before section 1,
emit a table: `| tool | answers | jump |` with each `jump` an anchor link (`#tool_name`) to its example.
The page then doubles as a scannable index, not just a scroll. Group rows by the three section themes.

### Page layout — ALL tools on ONE page

Everything lives in **`docs/usage/tool-examples.md`** with three H2 sections (T5 = section 1, T6 =
section 2, T7 = section 3). One page is fine because tabs and single compact JSON blocks keep it
scannable; 2-of-3 tab variants stay hidden until clicked. Put the Docusaurus `Tabs`/`TabItem` imports
(T7) at the very top of this one file. Wire this single page into nav in T9.

### T5 — Section 1: "## Enriching tools"

Theme line for the section intro: *"These tools show the LLM what the compiler sees but the source text
does not — inferred types, synthesized implicit arguments and conversions, resolved signatures."*

**Every example in this section follows the T4.5 template** (Answers → Input → plain-vs-enriched
contrast → :::tip takeaway → Replaces → `<details>` JSON). Use the **enrich contrast** (plain vs
annotated, both from `ToolRunner`). The illustration below shows the moving part — the live output
fence — but wrap it in the full template:

````markdown
### annotated_source — makes the compiler's invisible insertions explicit

Input (`Enrich.scala`):

```scala
// show the fixture source here — a plain fenced block is fine, OR an `mdoc:silent`
// fence containing the same code so mdoc also *compiles* it and proves it is valid Scala.
given intShow: Show[Int] with { def show(a: Int) = a.toString }
def render[A](a: A)(using sh: Show[A]): String = sh.show(a)
val out = render(List(1, 2, 3))
```

Tool output (produced live by the real analyzer):

```scala mdoc:passthrough
println("```json")
println(scalasemantic.docs.ToolRunner.run(
  "annotated_source",
  """{"uri":"doc-examples/src/main/scala/com/github/mercurievv/scalasemantic/docexamples/Enrich.scala","format":"annotated","annotationsOnly":true}"""))
println("```")
```

Then 1–2 sentences: point at the specific enrichment (e.g. the `(using intShow)` note and the inferred
`: String`) and what a plain text read would have missed.
````

Key mdoc mechanics:
- The **output** block is a `scala mdoc:passthrough` fence: mdoc executes the Scala, and whatever it
  `println`s becomes raw markdown. We wrap the JSON in a ```` ```json ```` fence so it renders as a code
  block. This is the "mdoc executes the code and shows the computed value" mechanism the maintainer
  wants — the value is computed by the real tool.
- The **input** block should ideally be an `mdoc:silent` fence of the fixture code so mdoc also
  compiles it (extra rot-protection that the snippet is valid Scala). Where the snippet references
  fixture-local `given`/traits, either inline enough context to compile, or keep it a plain (non-mdoc)
  fence and rely on `doc-examples` compiling the real file. Prefer real compilation where cheap.

Tools in this section (curated for the enrich story):

| Tool | Fixture focus | Args to pass (resolve real symbols via find_symbol first — see T8) |
| --- | --- | --- |
| `annotated_source` | `Enrich.scala` full file | `{uri, format:"annotated", annotationsOnly:true}` |
| `method_signature` | `render` with a `using` param | `{symbol:"…render()."}` (shows the using list the source hid) |
| `document_outline` | `Enrich.scala` | `{uri}` (compiler-rendered signatures, not source text) |
| `resolve_implicits` | `Show[Int]` given | `{type:"…/Show#"}` |
| `trace_implicit_chain` | `listShow` depends on `intShow` | `{type:"…/Show#"}` |
| `type_at_position` | a position on `render(List(1,2,3))` | `{uri, line, character}` (0-based) |

For each, the accompanying prose must name **what was enriched** and **what a grep/Read would miss**.

### T6 — Section 2: "## Exploration / edit-plan tools" (same page)

Theme line: *"These tools return one precise semantic answer — a symbol, a usage set, a hierarchy, an
edit plan — replacing whole-file reads and grep guesswork (which miss renames/implicits and over-match
comments and strings)."*

**Follow the T4.5 template.** Use the **cut contrast** (count line: illustrative grep hits vs real tool
count) as each example's contrast block. Emphasis on *what it replaces* — files/lines/tokens saved.

| Tool | Fixture focus (`Navigate.scala` / `Refactor.scala` / `Duplication.scala`) | Args |
| --- | --- | --- |
| `find_symbol` | resolve a plain name → symbol | `{query:"render"}` |
| `find_usages` | a symbol used in several places | `{symbol:"…"}` |
| `class_hierarchy` | small trait/class tree | `{symbol:"…#"}` |
| `find_overloads` | overloaded method | `{symbol:"…(+0)."}` |
| `members` | class with declared+inherited | `{symbol:"…#"}` |
| `call_path` | a→b→c chain | `{from:"…a().", to:"…c()."}` |
| `method_call_hierarchy` | same chain | `{symbol:"…a().", direction:"callees"}` |
| `value_flow` | a val flowing through calls | `{symbol:"…val."}` |
| `rename_plan` | symbol with usages | `{symbol:"…", newName:"…"}` (exact ranges, no comment over-match) |
| `move_plan` | top-level def | `{symbol:"…", newOwner:"…/"}` |
| `extract_method_plan` | a method range | `{uri, startLine, startCharacter, endLine, endCharacter}` |
| `structure` | whole doc-examples | `{}` (dep metrics; note it's project-scoped) |
| `smart_code_duplications` | `Duplication.scala` | `{minSize:15}` |

Prose emphasis on this page: **what it cuts** — e.g. `find_usages` returns N locations vs. reading M
files; `document_outline`/`structure` replace whole-file reads; `rename_plan` gives exact edit ranges
that grep would over/under-match.

### T7 — Section 3: "## Tools on modified code" (same page)

Purpose: show tools answering correctly against a **buffer edited since (or never) compiled**, using
the `source` parameter — the "what happens when the code is modified" story.

**Show the buffer diff first.** Above the tabs, render what changed — the original fixture line(s) vs
the edited line(s) as a small `diff` fenced block — so the before/after has a concrete anchor before the
reader opens the tabs.

**Three views per example, shown as Docusaurus tabs** (so the page stays compact — the reader switches
tabs instead of scrolling three stacked blocks):
1. `DB (committed)` — tool on the compiled fixture (baseline).
2. `PC (same code)` — `source` = the unmodified file; proves PC regeneration matches DB on identical
   code (fidelity check).
3. `PC (modified)` — `source` = the edited file; proves the answer tracks the edit with no recompile.

**Tabs mechanics (Docusaurus MDX):**
- Docusaurus renders `.md` as MDX, so add these imports **once at the very top** of the single
  `tool-examples.md` (mdoc passes non-fence content through unchanged):
  ```
  import Tabs from '@theme/Tabs';
  import TabItem from '@theme/TabItem';
  ```
- The output `scala mdoc:passthrough` fence prints the JSX wrapper around the three JSON code blocks,
  e.g.:
  ```scala mdoc:passthrough
  def block(json: String) = "\n```json\n" + json + "\n```\n"
  println("<Tabs>")
  println("<TabItem value=\"db\" label=\"DB (committed)\">")
  println(block(scalasemantic.docs.ToolRunner.run("type_at_position", argsOriginal)))
  println("</TabItem>")
  println("<TabItem value=\"pc-same\" label=\"PC (same code)\">")
  println(block(scalasemantic.docs.ToolRunner.runWithSource("type_at_position", argsOriginal, uri, originalCopy)))
  println("</TabItem>")
  println("<TabItem value=\"pc-mod\" label=\"PC (modified)\" default>")
  println(block(scalasemantic.docs.ToolRunner.runWithSource("type_at_position", argsModified, uri, editedCopy)))
  println("</TabItem>")
  println("</Tabs>")
  ```
  (Blank lines around each ```` ```json ```` fence matter — MDX needs them to parse the code block
  inside `<TabItem>`.)
- Pages 1–2 stay plain single-block (no tabs); tabs are only for this 3-view page.

Original before/after intent (now expressed as tabs 1 vs 3):

Use `ToolRunner.runWithSource(...)`, pointing `--source` at an *edited* copy of the fixture that lives
alongside the docs (e.g. `doc-examples/edited/Enrich_modified.scala`), and `--uri` at the fixture's
index uri. Cover the three `source`-aware tools:

- `type_at_position` — position resolves against the edited buffer (add a new val, query its type).
- `method_signature` — add a `using` param in the buffer; signature updates without recompiling.
- `extract_method_plan` — select a range in the edited buffer; plan reflects new locals.

Each with a one-line explanation: *the presentation compiler regenerated SemanticDB in memory from the
buffer, so the answer matches code that was never compiled to disk.* (This requires the PC-backed
`ToolCli` branch from T2; if PC wiring proves too heavy, fall back to: edit the fixture on disk,
`./mill docExamples.compile`, re-run the index tool — same before/after story, simpler, but note in the
page that it required a recompile.)

### T8 — Resolving the real symbol strings for the args

Tool args need SemanticDB symbol strings (grammar: `#` type, `.` term, `().` method, `(+N).` overload
— see `docs/reference/tools.md`). Do NOT guess them. Two options, pick one and keep it consistent:

1. **Author-time resolution (simplest):** after T1 compiles, run `find_symbol` via the CLI once per
   name and paste the resolved symbol into the page's args. Example:
   `java -cp <jar> …ToolCli --index <dir> --tool find_symbol --args '{"query":"render"}'`.
   Because symbols are stable for stable fixture code, this is fine and keeps pages readable.
2. **Fence-time resolution (most robust):** in the output fence, first call `find_symbol` in Scala,
   pull `.obj("symbols")(0)("symbol").str`, then feed it into the real tool. Slightly more code per
   example but survives fixture renames. Use this for `find_usages`/`rename_plan` where drift is
   likeliest.

Whichever you choose, the build still fails loudly if a symbol is wrong (tool returns `found:false` /
empty), so correctness stays enforced.

### T9 — Wire the pages into the site nav + build

- Add the single `tool-examples.md` page to the Docusaurus sidebar (`website/sidebars.js`; mdoc output
  goes to `website/docs`). Put it next to `docs/usage/examples.md`, and link it from
  `docs/reference/tools.md`.
- Ensure the docs run depends on the new build outputs (done in T4 via `forkArgs` referencing
  `mcp.assembly()` and `docExamples.compile()`).

---

## Build & verify

```
./mill docExamples.compile        # T1: fixtures emit SemanticDB
./mill mcp.assembly               # T2: real 3.8.4 tool jar
# smoke-test the CLI directly:
java -cp out/mcp/assembly.dest/out.jar com.github.mercurievv.scalasemantic.mcp.ToolCli \
  --index out/docExamples/compile.dest/classes --tool find_symbol --args '{"query":"render"}'
./mill docs.run                   # T3–T9: mdoc executes fences → website/docs/*.md
```

Acceptance criteria:

- `./mill docs.run` exits 0 and produces `website/docs/usage/tool-examples.md` with all three sections.
- Every tool-output block in those generated files contains **real JSON** (not `@…@`, not an mdoc
  error block). Grep the generated output for `mdoc-error`/stack traces → none.
- Deliberately break one arg (wrong symbol) and confirm the docs build **fails** — proving the pages
  are executed, not copied. Revert.
- Spot-check three outputs against the actual tool (run `ToolCli` by hand for the same args) → byte
  identical.

## Out of scope / notes

- The presentation-compiler branch (T2/T7) is the only heavy part; if time-boxed, ship Pages 1–2 first
  (index-only tools, no PC), then add Page 3.
- Do not delete or rewrite the existing `docs/usage/examples.md` in the same change; these pages
  supersede it, but removal is a separate decision.
- Keep fixtures and outputs small — the docs double as a token-economy showcase; bloated JSON undercuts
  the "what they cut" message.
```
