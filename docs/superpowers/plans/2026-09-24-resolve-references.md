# `resolve_references` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An MCP tool `resolve_references(files)` that, for given line ranges of given files, reports every name the code refers to — resolved symbol, origin, kind, role — or `resolved: false` with deterministic near-miss suggestions, plus a one-shot launcher mode so a shell hook can call it.

**Architecture:** Two sources are joined in the `analysis` module: scalameta parses the buffer and yields every *name token* in the requested ranges (this is what makes an unresolved name observable at all); the presentation compiler regenerates SemanticDB for the same buffer and yields an occurrence per *resolved* name. A token with no covering occurrence is unresolved. Origin comes from the SemanticDB index (`project`) versus the symbol's namespace (`stdlib`) versus everything else (`classpath`). No dotty-internal API is needed — `pc` is used only through the existing `PresentationCompilerBackend`.

**Tech Stack:** Scala 3.8.4, Mill 1.1.7, `org.scalameta:scalameta` (already a dependency; `dialects.Scala3(text).parse[Source]` is used today in `DuplicationAnalyzer`), upickle/ujson, MUnit.

**Spec:** `/Users/viktorskalinins/IdeaProjects/my/scala-coding-guidelines/docs/superpowers/specs/2026-09-24-diff-scoped-verification-design.md` (§1 is this plan; §2–§4 are the skreeep2 plan)

## Global Constraints

- Wartremover is ON in `analysis` and `mcp`: no `var`, `null`, `asInstanceOf`, `throw` (`error(...)` in `McpTools` is the sanctioned exit), no `.head`/`Seq` indexing — use `headOption`, `lift`, `zipWithIndex`.
- The tool carries **no policy**: no allowlist argument, no git, no diff parsing. It returns semantic facts only.
- `ranges` are `[startLine, endLine]`, 0-based, **end exclusive**, as `git diff -U0` hunk headers yield; a reference outside every range never appears in the result.
- `origin` is `project` when the symbol's document is in the SemanticDB index, `stdlib` for symbols starting `scala/` or `java/`, `classpath` otherwise.
- `suggestions` are deterministic near-misses only — same owner and simple name within Levenshtein distance 2, or an exact simple-name match under a different owner. Empty when nothing is close. Never applied, never a guess.
- Bound: 200 rows per file; `omitted` counts what the bound cut.
- Tool conventions copy `type_at_position`: `uri` is the SemanticDB-form path relative to the project root; `source` is the buffer's current text and enables the PC overlay; without `source` the on-disk file is read and the result carries `pcFallback`.
- Tests that boot a presentation compiler go in `pc`/`analysis` PC suites or `McpPcSuite` — never in the stryker-filtered `McpSuite`; `munitTimeout` 120 s on those suites.
- Mill runs take minutes: run them in the FOREGROUND with a long timeout, one at a time, filtering output. Reformat per module (`analysis.reformat`, `analysis.test.reformat`, …) — combining module targets in one invocation errors.
- Commits end with `Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU`; no `Co-Authored-By`. Never `sbt`. Never `git checkout --`/`git restore`/`git stash`/`rm -rf`.

## File structure

- `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/NameTokens.scala` — pure scalameta extraction of name tokens in ranges. (Task 1)
- `analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/ReferenceResult.scala` — the upickle model. (Task 2)
- `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/Analyzer.scala` — `resolveReferences`, the join, origin and suggestions. (Task 2)
- `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala` — the `resolve_references` tool. (Task 3)
- `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/Mcp.scala` + `launcher/.../Launcher.scala` + `mcp/.../Main.scala` — the one-shot `call` mode. (Task 4)
- Docs: `docs/reference/tools.md`, `docs/usage/tool-examples.md`, `README.md`. (Task 4)

---

### Task 1: `NameTokens` — every name a range mentions

**Files:**
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/NameTokens.scala`
- Test: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/NameTokensSuite.scala`

**Interfaces:**
- Produces:

```scala
package com.github.mercurievv.scalasemantic.analysis

/** One name the source text mentions: its 0-based position, the text as written, and what kind of
  * name tree it came from. Syntax only — nothing here knows whether the name resolves.
  */
final case class NameToken(line: Int, character: Int, name: String, kind: String, role: String)

object NameTokens:
  /** Every name token of `text` whose start position falls inside one of `ranges`
    * (`[startLine, endLineExclusive)`, 0-based). An empty `ranges` list means the whole file.
    * `None` when the text does not parse at all (a syntax error, not a resolution failure).
    */
  def in(text: String, ranges: Seq[(Int, Int)]): Option[List[NameToken]]
```

  `kind` is `type` for `Type.Name`, `term` for `Term.Name` in a value position, `method` for a `Term.Name` that is the `fun` of an `Apply`, `package` for names under a `Pkg`/`Importer` qualifier chain. `role` is `definition` when the name is the `name` of a `Defn`/`Decl`/`Pat.Var`/parameter, `use` otherwise.

- [ ] **Step 1: Write the failing test**

```scala
package com.github.mercurievv.scalasemantic.analysis

class NameTokensSuite extends munit.FunSuite:

  private val src =
    """package demo
      |
      |import cats.effect.IO
      |
      |object Holder:
      |  val label: String = "x"
      |  def run(n: Int): IO[Unit] = IO.pure(()).void
      |  def broken: Int = undefinedName + 1
      |""".stripMargin

  private def namesIn(ranges: (Int, Int)*): List[String] =
    NameTokens.in(src, ranges).getOrElse(fail("did not parse")).map(_.name)

  test("an empty range list yields every name in the file") {
    val all = namesIn()
    assert(all.contains("IO"), all.toString)
    assert(all.contains("label"), all.toString)
    assert(all.contains("undefinedName"), all.toString)
  }

  test("a range selects only the names whose position is inside it") {
    // line 7 (0-based) is `def broken: Int = undefinedName + 1`
    val names = namesIn((7, 8))
    assert(names.contains("undefinedName"), names.toString)
    assert(names.contains("broken"), names.toString)
    assert(!names.contains("label"), names.toString)
    assert(!names.contains("IO"), names.toString)
  }

  test("kinds: type, term, method") {
    val toks = NameTokens.in(src, Seq((6, 7))).getOrElse(fail("did not parse"))
    val byName = toks.map(t => t.name -> t.kind).toMap
    assertEquals(byName.get("Int"), Some("type"))
    assertEquals(byName.get("IO"), Some("type"))       // in `IO[Unit]`, a Type.Name
    assertEquals(byName.get("pure"), Some("method"))   // the `fun` of an Apply
    assertEquals(byName.get("n"), Some("term"))
  }

  test("roles: a definition's own name vs a use") {
    val toks = NameTokens.in(src, Seq((5, 6))).getOrElse(fail("did not parse"))
    assertEquals(toks.find(_.name == "label").map(_.role), Some("definition"))
    assertEquals(toks.find(_.name == "String").map(_.role), Some("use"))
  }

  test("positions are 0-based and point at the name's first character") {
    val toks = NameTokens.in(src, Seq((7, 8))).getOrElse(fail("did not parse"))
    val undefined = toks.find(_.name == "undefinedName").getOrElse(fail("not found"))
    assertEquals(undefined.line, 7)
    assertEquals(src.linesIterator.toList.lift(7).map(_.indexOf("undefinedName")), Some(undefined.character))
  }

  test("a file that does not parse yields None, not an empty list") {
    assertEquals(NameTokens.in("object Broken:\n  def f(: Int = 1\n", Nil), None)
  }

  test("import selectors are names too (an invented import is a hallucination)") {
    val names = namesIn((2, 3))
    assert(names.contains("IO"), names.toString)
    assert(names.contains("cats"), names.toString)
  }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.NameTokensSuite`
Expected: compile error — `NameTokens` not found.

- [ ] **Step 3: Implement**

```scala
package com.github.mercurievv.scalasemantic.analysis

import scala.meta.*
import scala.util.Try

/** One name the source text mentions: its 0-based position, the text as written, and what kind of
  * name tree it came from. Syntax only — nothing here knows whether the name resolves. Pairing
  * these with the presentation compiler's SemanticDB occurrences is what makes an UNRESOLVED name
  * observable: the compiler emits an occurrence for every name it resolved, so a token with no
  * occurrence over it is a name it could not.
  */
final case class NameToken(line: Int, character: Int, name: String, kind: String, role: String)

object NameTokens:

  /** Every name token of `text` whose start position falls inside one of `ranges`
    * (`[startLine, endLineExclusive)`, 0-based); an empty `ranges` means the whole file. `None`
    * when the text does not parse — a syntax error is the caller's problem to report as such, not
    * a list of zero references.
    */
  def in(text: String, ranges: Seq[(Int, Int)]): Option[List[NameToken]] =
    Try(dialects.Scala3(text).parse[Source].get).toOption.map { tree =>
      val inRange: Position => Boolean =
        if ranges.isEmpty then _ => true
        else pos => ranges.exists((from, until) => pos.startLine >= from && pos.startLine < until)

      // `collect` over the whole tree, then filter by position: scalameta's traversal is cheap and
      // a tree-pruning walk would have to re-implement the containment logic for every node type.
      val tokens = tree.collect {
        case t: Type.Name if inRange(t.pos) => NameToken(t.pos.startLine, t.pos.startColumn, t.value, "type", roleOf(t))
        case t: Term.Name if inRange(t.pos) => NameToken(t.pos.startLine, t.pos.startColumn, t.value, kindOfTerm(t), roleOf(t))
        case t: Name.Indeterminate if inRange(t.pos) => NameToken(t.pos.startLine, t.pos.startColumn, t.value, "package", "use")
      }
      tokens.distinctBy(t => (t.line, t.character, t.name))
    }

  /** A `Term.Name` that is the callee of an application is a method reference; anything else in a
    * term position is a value reference. Both are `term`-namespace names in SemanticDB, so the
    * distinction is for the reader (and for a policy that treats calls differently), not for
    * resolution.
    */
  private def kindOfTerm(t: Term.Name): Boolean => String = _ => ""
  // NOTE to the implementer: the line above is a placeholder shape only — write `kindOfTerm` as a
  // plain `private def kindOfTerm(t: Term.Name): String` that inspects `t.parent`:
  //   * parent is `Term.Apply`/`Term.ApplyType`/`Term.ApplyInfix` with this name as `fun`/`op` -> "method"
  //   * parent is `Term.Select` whose `name` is this name and whose own parent is an Apply -> "method"
  //   * parent is `Pkg`/`Importer` ref chain -> "package"
  //   * otherwise -> "term"

  /** `definition` when the token IS the name being introduced (a def/val/var/class/object/type's
    * own name, a parameter's name, a pattern variable); `use` otherwise.
    */
  private def roleOf(t: Tree): String =
    t.parent match
      case Some(p: Defn.Def) if p.name eq t      => "definition"
      case Some(p: Defn.Val)                     => if p.pats.exists(_ eq t) then "definition" else "use"
      case Some(p: Defn.Var)                     => if p.pats.exists(_ eq t) then "definition" else "use"
      case Some(p: Defn.Class) if p.name eq t    => "definition"
      case Some(p: Defn.Trait) if p.name eq t    => "definition"
      case Some(p: Defn.Object) if p.name eq t   => "definition"
      case Some(p: Defn.Type) if p.name eq t     => "definition"
      case Some(p: Decl.Def) if p.name eq t      => "definition"
      case Some(p: Decl.Val)                     => if p.pats.exists(_ eq t) then "definition" else "use"
      case Some(p: Term.Param) if p.name eq t    => "definition"
      case Some(p: Type.Param) if p.name eq t    => "definition"
      case Some(p: Pat.Var) if p.name eq t       => "definition"
      case _                                     => "use"
```

Write `kindOfTerm` as the NOTE describes (a real `private def kindOfTerm(t: Term.Name): String`), and delete the placeholder line. Check each scalameta case-class member name against the version on the classpath (`Defn.Val.pats`, `Term.Param.name`, `Term.Apply.fun`) before relying on it — `scala.meta` 4.13.x is what `build.mill`'s `V.scalameta` pins.

- [ ] **Step 4: Run the tests**

Run: `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.NameTokensSuite`
Expected: 7 tests pass. Then `./mill analysis.test` (no regressions).

- [ ] **Step 5: Commit**

```bash
git add analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/NameTokens.scala analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/NameTokensSuite.scala
git commit -m "analysis: NameTokens — every name a line range mentions, syntax only

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 2: The join — `Analyzer.resolveReferences`, origin, suggestions

**Files:**
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/ReferenceResult.scala`
- Modify: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/Analyzer.scala` (after `typeAtPosition`, ~line 1067)
- Test: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/ResolveReferencesSuite.scala` (boots a PC — give it `munitTimeout` 120 s)

**Interfaces:**
- Consumes: `NameTokens.in(text, ranges)` (Task 1); `Analyzer.bufferOnly(fileUri, code, docUri)` (an analyzer over JUST the PC's regenerated document); `index.document(uri).occurrences`; `AnalyzerHelpers.rangeContains`.
- Produces:

```scala
package com.github.mercurievv.scalasemantic.model
import upickle.default.*

final case class Reference(
    line: Int, character: Int, name: String, kind: String, role: String, resolved: Boolean,
    symbol: Option[String], origin: Option[String], suggestions: List[String]) derives ReadWriter

final case class FileReferences(
    uri: String, references: List[Reference], omitted: Int,
    pcFallback: Boolean, error: Option[String]) derives ReadWriter

final case class ReferenceResult(files: List[FileReferences]) derives ReadWriter
```

```scala
// Analyzer
/** Every name the given ranges of `code` mention, each resolved (or not) against this index plus
  * the presentation compiler's view of the buffer. `None` when there is no PC backend for `docUri`.
  */
def resolveReferences(fileUri: URI, code: String, docUri: String, ranges: Seq[(Int, Int)]): Option[FileReferences]
```

- [ ] **Step 1: Write the failing test**

```scala
package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

class ResolveReferencesSuite extends munit.FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration("120s")

  private val workspace = java.nio.file.Files.createTempDirectory("resolve-refs").nn
  private val file = workspace.resolve("Refs.scala").nn
  private val uri = file.toUri

  private val src =
    """package demo
      |
      |object Refs:
      |  val label: String = "x"
      |  def good: String = label.trim
      |  def bad: String = label.trimm
      |  def missing: String = nowhereAtAll
      |""".stripMargin

  java.nio.file.Files.writeString(file, src)

  private def refs(ranges: (Int, Int)*) =
    PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      val az = Analyzer(new SemanticIndex(Vector.empty), Some(backend))
      az.resolveReferences(uri, src, "Refs.scala", ranges).getOrElse(fail("no PC backend"))
    }

  test("a resolved stdlib reference carries its symbol and origin") {
    val r = refs((4, 5))
    val trim = r.references.find(_.name == "trim").getOrElse(fail(r.references.toString))
    assert(trim.resolved, trim.toString)
    assert(trim.symbol.exists(_.startsWith("scala/")) || trim.symbol.exists(_.startsWith("java/")), trim.toString)
    assertEquals(trim.origin, Some("stdlib"))
  }

  test("an unresolved member is reported with a near-miss suggestion") {
    val r = refs((5, 6))
    val bad = r.references.find(_.name == "trimm").getOrElse(fail(r.references.toString))
    assert(!bad.resolved, bad.toString)
    assertEquals(bad.symbol, None)
    assert(bad.suggestions.exists(_.contains("trim")), bad.suggestions.toString)
  }

  test("an unresolved free name is reported with no suggestion when nothing is close") {
    val r = refs((6, 7))
    val missing = r.references.find(_.name == "nowhereAtAll").getOrElse(fail(r.references.toString))
    assert(!missing.resolved, missing.toString)
    assertEquals(missing.suggestions, Nil)
  }

  test("references outside the ranges never appear") {
    val r = refs((6, 7))
    assert(!r.references.exists(_.name == "trimm"), r.references.toString)
    assert(!r.references.exists(_.name == "good"), r.references.toString)
  }

  test("a definition's own name is role=definition and resolves") {
    val r = refs((3, 4))
    val label = r.references.find(_.name == "label").getOrElse(fail(r.references.toString))
    assertEquals(label.role, "definition")
    assert(label.resolved, label.toString)
  }

  test("a syntax error is reported as an error, not as zero references") {
    val broken = "object X:\n  def f(: Int = 1\n"
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(new SemanticIndex(Vector.empty), Some(backend))
        .resolveReferences(uri, broken, "Refs.scala", Seq((0, 2))).getOrElse(fail("no PC backend"))
    }
    assert(r.error.exists(_.nonEmpty), r.toString)
    assertEquals(r.references, Nil)
  }

  test("the 200-row bound is honoured and counted") {
    val many = (1 to 120).map(i => s"  val v$i: String = label").mkString("\n")
    val big = s"package demo\n\nobject Big:\n  val label: String = \"x\"\n$many\n"
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(new SemanticIndex(Vector.empty), Some(backend))
        .resolveReferences(uri, big, "Refs.scala", Nil).getOrElse(fail("no PC backend"))
    }
    assertEquals(r.references.size, 200)
    assert(r.omitted > 0, r.omitted.toString)
  }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.ResolveReferencesSuite`
Expected: compile error — `resolveReferences` not found.

- [ ] **Step 3: Implement the model and the join**

`ReferenceResult.scala` holds the three case classes from the Interfaces block above, each `derives ReadWriter`.

In `Analyzer`:

```scala
  /** Every name the given `ranges` of `code` mention (0-based, end exclusive; empty = whole file),
    * each resolved against the presentation compiler's regenerated view of the buffer and this
    * index (spec 2026-09-24-diff-scoped-verification-design §1). Two sources are joined: scalameta
    * gives EVERY name token, the PC gives an occurrence per name it RESOLVED — so a token with no
    * occurrence over it is exactly a name the compiler could not resolve, which neither source can
    * say on its own. `None` when there is no PC backend for `docUri`.
    */
  def resolveReferences(
      fileUri: URI,
      code: String,
      docUri: String,
      ranges: Seq[(Int, Int)]
  ): Option[FileReferences] =
    bufferOnly(fileUri, code, docUri).map { buffer =>
      NameTokens.in(code, ranges) match
        case None =>
          FileReferences(docUri, Nil, 0, pcFallback = false, Some("the file does not parse"))
        case Some(tokens) =>
          val occurrences = buffer.occurrencesOf(docUri)
          val rows = tokens.map { t =>
            occurrences.find(occ => occ.range.exists(r => h.rangeContains(r, t.line, t.character))) match
              case Some(occ) if occ.symbol.nonEmpty && !occ.symbol.startsWith("local") =>
                Reference(t.line, t.character, t.name, t.kind, t.role, resolved = true,
                  Some(occ.symbol), Some(originOf(occ.symbol)), Nil)
              case Some(occ) if occ.symbol.startsWith("local") =>
                // A local (a parameter, a local val) resolves but has no global symbol; it is not a
                // hallucination and must not be reported as one.
                Reference(t.line, t.character, t.name, t.kind, t.role, resolved = true,
                  Some(occ.symbol), Some("project"), Nil)
              case _ =>
                Reference(t.line, t.character, t.name, t.kind, t.role, resolved = false,
                  None, None, suggestionsFor(t.name))
          }
          FileReferences(docUri, rows.take(200), math.max(0, rows.size - 200), pcFallback = false, None)
    }

  /** `project` when the index holds the symbol's defining document, `stdlib` for the Scala and Java
    * namespaces, `classpath` for everything else. The harness's policy reads this; the tool itself
    * has no policy.
    */
  private def originOf(symbol: String): String =
    if symbol.startsWith("scala/") || symbol.startsWith("java/") then "stdlib"
    else if h.definitionUri(symbol).isDefined then "project"
    else "classpath"

  /** Deterministic near-misses for a name that did not resolve: a global symbol in the index whose
    * simple name is within edit distance 2, or equal. Never more than three, always sorted, so the
    * output is stable across runs.
    */
  private def suggestionsFor(name: String): List[String] =
    if name.length < 3 then Nil
    else
      index.globalSymbols.iterator
        .filter(sym => AnalyzerHelpers.editDistanceAtMost(index.displayName(sym), name, 2))
        .toList
        .sorted
        .take(3)
```

Two helpers this needs, added where they belong:

- `Analyzer.occurrencesOf(uri: String): Seq[s.SymbolOccurrence]` — a small accessor over `index.document(uri).toSeq.flatMap(_.occurrences)` (the `bufferOnly` analyzer's index holds exactly the PC document). If an equivalent accessor already exists, use it instead of adding one.
- `AnalyzerHelpers.editDistanceAtMost(a: String, b: String, max: Int): Boolean` — a bounded Levenshtein: return `false` immediately when `math.abs(a.length - b.length) > max`, otherwise compute the standard DP over two rows and compare. Add a small pure test for it in the existing `AnalyzerHelpers` suite (`editDistanceAtMost("trim", "trimm", 2) == true`, `("trim", "wholly", 2) == false`).
- `SemanticIndex.globalSymbols` — an iterator over the index's known global symbols. If the index already exposes such a collection (grep `isGlobal` in `SemanticIndex`/`AnalyzerHelpers`), reuse it rather than adding a field; if it does not, derive it from the documents' `symbols` and note the cost in the report.

- [ ] **Step 4: Run the tests**

Run: `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.ResolveReferencesSuite` → 7 pass. Then `./mill analysis.test` and `./mill mcp.compile`.

Note for the suggestion tests: the index in the suite is empty, so a suggestion can only come from the PC document's own symbols. If `label.trimm`'s near-miss `trim` is not reachable from an empty index, extend `suggestionsFor` to also consider the buffer document's symbols (the `bufferOnly` analyzer's own index) — that is the correct behaviour anyway, since a typo of a local member must be suggestible. Record the choice in the report.

- [ ] **Step 5: Commit**

```bash
git add analysis/
git commit -m "analysis: resolveReferences — join name tokens with PC occurrences, with origin and near-misses

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 3: The `resolve_references` MCP tool

**Files:**
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala` (a new `toolDef` after `type_at_position`)
- Modify: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpPcSuite.scala`
- Modify: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpSuite.scala` (the two tool-count assertions — they were bumped to 28 by the `hole_context` work; this makes 29)
- Modify: `regressions/unit-tests.json` (register the new test names, as the neighbours are)

**Interfaces:**
- Consumes: `Analyzer.resolveReferences(fileUri, code, docUri, ranges)` (Task 2); `argUri`, `readFileText`, `pcFallbackFields`, `error`, `notFound`, `az.isDocumentIndexed` — all existing helpers in `McpTools`.
- Produces: the tool `resolve_references`, arguments `files` (required array of `{uri, ranges, source?}`), result `{"files": [ … ]}` per §1 of the spec.

- [ ] **Step 1: Write the failing tests** (append to `McpPcSuite`, reusing its `req` helper and `PresentationCompilerBackend.useCurrentJvm` pattern; `Mcp.handle` returns `Option[ujson.Value]`, so use the file's `resp.getOrElse(fail("no response"))(...)` idiom)

```scala
  test("resolve_references: an unresolved name in range is reported, a resolved one carries origin") {
    val root = java.nio.file.Files.createTempDirectory("mcp-refs").nn
    val file = root.resolve("Refs.scala").nn
    val source =
      """package demo
        |object Refs:
        |  val label: String = "x"
        |  def bad: String = label.trimm
        |""".stripMargin
    java.nio.file.Files.writeString(file, source)
    PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
      val tools = McpTools.all(Analyzer(new SemanticIndex(Vector.empty), Some(backend)), root)
      val resp = Mcp.handle(req("tools/call", ujson.Obj("name" -> "resolve_references",
        "arguments" -> ujson.Obj("files" -> ujson.Arr(ujson.Obj(
          "uri" -> "Refs.scala",
          "ranges" -> ujson.Arr(ujson.Arr(ujson.Num(3), ujson.Num(4))),
          "source" -> source))))), tools)
      val json = ujson.read(resp.getOrElse(fail("no response"))("result")("content")(0)("text").str)
      val refs = json("files")(0)("references").arr
      assert(refs.exists(r => r("name").str == "trimm" && !r("resolved").bool), refs.toString)
      assert(refs.exists(r => r("name").str == "label" && r("resolved").bool), refs.toString)
      assert(!refs.exists(r => r("name").str == "String" && r("line").num == 2), "out-of-range row leaked")
    }
  }

  test("resolve_references: several files in one call") {
    // Build two files in one temp root, pass both in `files`, assert two entries come back, each
    // keyed by its own uri and carrying its own references.
  }

  test("resolve_references: without `source` the disk file is read and pcFallback is set") {
    // Same shape as above, omitting "source"; assert json("files")(0)("pcFallback").bool is true
    // (the index is empty, so the document is not indexed).
  }

  test("resolve_references: without a classpath the tool returns the no-classpath error") {
    val root = java.nio.file.Files.createTempDirectory("mcp-refs-nocp").nn
    val tools = McpTools.all(Analyzer(new SemanticIndex(Vector.empty)), root)
    val resp = Mcp.handle(req("tools/call", ujson.Obj("name" -> "resolve_references",
      "arguments" -> ujson.Obj("files" -> ujson.Arr(ujson.Obj("uri" -> "X.scala", "ranges" -> ujson.Arr()))))), tools)
    val text = resp.getOrElse(fail("no response"))("result")("content")(0)("text").str
    assert(text.toLowerCase.contains("classpath"), text)
  }
```

Write the two abbreviated tests in full, copying the first test's scaffolding — they are independent tests, not shared state.

- [ ] **Step 2: Run to verify failure**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.McpPcSuite`
Expected: the new tests fail with `unknown tool: resolve_references`.

- [ ] **Step 3: Implement the tool**

```scala
      toolDef(
        tool(
          "resolve_references",
          "Every name the given LINE RANGES of the given files refer to, and whether it resolves — " +
            "the deterministic check for hallucinated symbols. Each row carries the name as written, " +
            "its `kind` (type/term/method/package) and `role` (use/definition), and either " +
            "`resolved: true` with the SemanticDB `symbol` and its `origin` (`project` when this " +
            "index defines it, `stdlib` for scala/java, `classpath` otherwise) or `resolved: false` " +
            "with deterministic near-miss `suggestions`. `ranges` are [startLine, endLineExclusive], " +
            "0-based, as `git diff -U0` yields; an empty list means the whole file, and a reference " +
            "outside every range never appears. Pass `source` with the file's CURRENT text " +
            "(uncompiled is fine); without it the on-disk file is read and the result is marked " +
            "`pcFallback`. Needs the server started with a classpath. Policy — which symbols a module " +
            "MAY use — is the caller's, never this tool's.",
          List(
            ("files", "array", "objects: {uri: string, ranges: [[startLine, endLineExclusive], …], source?: string}")
          ),
          List("files")
        ) { a =>
          val entries = a.obj.get("files").toList.flatMap(_.arr)
          if entries.isEmpty then error("resolve_references: `files` must hold at least one {uri, ranges} object")
          else
            val results = entries.map { entry =>
              val uri = argUri(entry, "uri")
              val file = root.resolve(uri.value)
              val ranges = entry.obj.get("ranges").toSeq.flatMap(_.arr).flatMap { r =>
                val pair = r.arr.map(_.num.toInt)
                (pair.lift(0), pair.lift(1)) match
                  case (Some(from), Some(until)) => Some((from, until))
                  case _                         => None
              }
              val (code, fromDisk) = entry.obj.get("source").map(_.str) match
                case Some(src) => (Some(src), false)
                case None      => (readFileText(file), true)
              code match
                case None => ujson.Obj("uri" -> ujson.Str(uri.value), "references" -> ujson.Arr(),
                                       "omitted" -> ujson.Num(0), "error" -> ujson.Str(s"no source: pass `source` or make ${uri.value} exist on disk"))
                case Some(text) =>
                  az.resolveReferences(file.toUri, text, uri.value, ranges) match
                    case None => ujson.Obj("uri" -> ujson.Str(uri.value), "error" -> ujson.Str("no presentation compiler"))
                    case Some(res) =>
                      val base = upickle.default.writeJs(res)
                      if fromDisk && !az.isDocumentIndexed(uri) then
                        ujson.Obj.from(base.obj.toSeq ++ pcFallbackFields(uri.value))
                      else base
            }
            if results.forall(r => r.obj.get("error").exists(_.str == "no presentation compiler")) then
              error(<the repo's existing no-classpath message — grep `classpath` in this file and reuse the wording hole_context uses>)
            else ujson.Obj("files" -> ujson.Arr(results*))
        }
      ),
```

Replace the `<…>` with the actual string used by `hole_context` (added by the 2026-09-21 plan) so both tools speak with one voice. Check `argUri`'s signature — it takes the args object; here it is applied to each entry, so pass the entry.

- [ ] **Step 4: Run the tests**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.McpPcSuite` → all pass; then `./mill mcp.test` (the tool-count assertions in `McpSuite` need bumping to 29 — the failure names them). Add the four new test names to `regressions/unit-tests.json`.

- [ ] **Step 5: Commit**

```bash
git add mcp/ regressions/unit-tests.json
git commit -m "mcp: resolve_references tool — diff-scoped reference resolution, batched per call

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 4: One-shot `call` mode and docs

**Files:**
- Modify: `launcher/src/main/scala/com/github/mercurievv/scalasemantic/Launcher.scala:9-19` (subcommand dispatch)
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/Main.scala` (a `callTool` entry beside `serveMcp`)
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/Mcp.scala` (a `callOnce` that builds the same state `serve` builds, runs one tool, returns the JSON)
- Modify: `docs/reference/tools.md`, `docs/usage/tool-examples.md`, `README.md`
- Test: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/CallOnceSuite.scala` (new; PC-free — use a tool that needs no classpath, e.g. `find_symbol`)

**Interfaces:**
- Consumes: `Mcp.serve`'s existing state construction (index from root, classpath discovery, `ModulePresentationCompilerBackends`) — reuse the same function rather than duplicating discovery.
- Produces: `scalasemantic-mcp call <tool> <json-args> [root] [classpath]` printing the tool's JSON result to stdout and exiting 0, or printing the error to stderr and exiting 1. Also `Mcp.callOnce(root: String, classpath: Option[String], tool: String, args: ujson.Value): Either[String, ujson.Value]`.

- [ ] **Step 1: Write the failing test**

```scala
package com.github.mercurievv.scalasemantic.mcp

class CallOnceSuite extends munit.FunSuite:

  test("callOnce runs one tool against the root's index and returns its JSON") {
    val res = Mcp.callOnce(".", None, "find_symbol", ujson.Obj("name" -> "Analyzer"))
    val json = res.fold(err => fail(err), identity)
    assert(json.obj.nonEmpty, json.toString)
  }

  test("callOnce names an unknown tool instead of failing silently") {
    val res = Mcp.callOnce(".", None, "no_such_tool", ujson.Obj())
    assert(res.isLeft, res.toString)
    assert(res.swap.exists(_.contains("no_such_tool")), res.toString)
  }
```

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.CallOnceSuite` — expect a compile error.

- [ ] **Step 2: Implement `callOnce`**

In `Mcp.scala`, beside `serve`:

```scala
  /** Run ONE tool and return its result — the entry a shell hook uses, which cannot talk to the
    * agent session's own server (that server belongs to the agent's process). Builds the same state
    * `serve` builds (index from `root`, classpath discovery, presentation-compiler backends), runs
    * the tool, and tears the backends down before returning.
    */
  def callOnce(
      root: String,
      classpath: Option[String],
      tool: String,
      args: ujson.Value
  ): Either[String, ujson.Value] = …
```

Find the function `serve` uses to build its state (around `ModulePresentationCompilerBackends(cp, rootPath, log)`, ~line 546) and call it here; close the backends in a `try`/`finally`. Return `Left` for an unknown tool, naming it and listing the available names, exactly as `ToolCli` does.

In `Main.scala`, add beside `serveMcp`:

```scala
private[scalasemantic] def callTool(args: Seq[String]): Unit =
  val (flags, positional) = args.partition(_.startsWith("--"))
  val _ = flags
  (positional.lift(0), positional.lift(1)) match
    case (Some(tool), Some(argsJson)) =>
      Mcp.callOnce(positional.lift(2).getOrElse("."), positional.lift(3), tool, ujson.read(argsJson)) match
        case Right(result) => println(ujson.write(result, indent = 2))
        case Left(err)     => System.err.println(err); sys.exit(1)
    case _ =>
      System.err.println("usage: scalasemantic-mcp call <tool> '<json-args>' [root] [classpath]")
      sys.exit(2)
```

and in `Launcher.run`, before the `serve` case:

```scala
      case Some("call") =>
        callTool(args.drop(1).toList)
```

`Launcher.run` currently takes only a `serve` callback; give it a second callback (or pass a `call` function the same way) rather than importing `Main` into `launcher` — `launcher` must not depend on `mcp`. Check the module graph in `build.mill` before wiring; if `launcher` cannot see `Mcp`, add the parameter to `run` and pass `callTool` from `Main`.

- [ ] **Step 3: Run the tests**

Run: `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.CallOnceSuite` → 2 pass. Then `./mill launcher.test mcp.test`.

Manual check (record the output in the report):

```bash
./mill mcp.runMain com.github.mercurievv.scalasemantic.mcpServer call find_symbol '{"name":"Analyzer"}' .
```

- [ ] **Step 4: Docs**

- `docs/reference/tools.md`: a table row — `| \`resolve_references\` | Every name the given line ranges refer to, resolved or not, with origin and near-miss suggestions. |`
- `docs/usage/tool-examples.md`: add the row to the "Compiler view" table and a `### resolve_references` section: what it tells you, then a captured example (run the tool through `call` on a scratch file with one good and one bad reference, paste the JSON trimmed to ~20 lines, prefaced by the same "captured run" caveat the `hole_context` section uses — the docs build starts no presentation compiler).
- `README.md` line ~34: add `` `resolve_references` `` to the tool list.
- `README.md` (usage section) and `docs/reference/tools.md`: one line documenting `scalasemantic-mcp call <tool> '<json>' [root] [classpath]` as the one-shot entry for scripts and hooks.

- [ ] **Step 5: Verify and commit**

Run `./mill __.compile`, `./mill __.test`, `./mill __.checkFormat` — all green (the full test run takes several minutes; run it once at the end).

```bash
git add launcher/ mcp/ docs/ README.md
git commit -m "launcher+docs: one-shot `call` mode; resolve_references documented with a captured example

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

## Self-review

- **Spec coverage.** §1's contract: `files`/`ranges`/`source` input → Task 3; the token↔occurrence join that makes "unresolved" observable → Tasks 1–2; `kind`/`role` → Task 1; `origin` → Task 2; `suggestions` (edit distance 2, same or different owner, empty when nothing close) → Task 2; the 200-row bound and `omitted` → Task 2; `pcFallback` and the no-classpath error → Task 3; the one-shot CLI → Task 4. §4's ScalaSemanticMCP test list: resolved project/classpath/stdlib symbol, unresolved-with-suggestion, out-of-range absence, definition vs use, error-tolerance, the cap, multi-file batch, `pcFallback`, JSON envelope, no-classpath error, goldens — Tasks 1–3, with the tool-count golden named explicitly in Task 3. §2–§4 of the spec are the skreeep2 plan, not this one.
- **Known gap, deliberate:** a *classpath* symbol's `origin` is asserted only indirectly (the `stdlib` test covers the namespace branch, and `project` requires an index). Task 2's implementer should add a `classpath`-origin assertion if the suite's PC classpath makes one cheap (e.g. a `cats.effect.IO` reference), and say so in the report if it does not.
- **Placeholders:** Task 1's `kindOfTerm` is given as a NOTE describing the four cases rather than final code, because the exact scalameta parent shapes must be checked against the pinned version; every other step carries real code. Task 3's no-classpath string is deliberately "reuse the existing wording" — the string exists in the repo and must not be re-invented.
- **Type consistency:** `NameToken(line, character, name, kind, role)` (Task 1) ↔ the join's construction (Task 2); `FileReferences(uri, references, omitted, pcFallback, error)` ↔ the tool's JSON (Task 3) ↔ the spec's envelope; `Analyzer.resolveReferences(fileUri, code, docUri, ranges)` ↔ its call site; `Mcp.callOnce(root, classpath, tool, args)` ↔ `callTool`'s positional order.
