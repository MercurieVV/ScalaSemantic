# Typed-Hole Context Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new MCP tool `hole_context(uri, line, character, source?)` that answers, from the Scala 3 compiler, what type a position demands and what in reach satisfies it (bindings, one-step producers, applicable givens, the types involved), plus a `typed-holes` skill that makes an agent write `???` skeletons first and fill holes one at a time with that context.

**Architecture:** A new `pc`-module class `HoleContext` owns a dotty `InteractiveDriver` built from the same classpath/options as the module's `PresentationCompilerBackend`, reuses the PC's own `InferCompletionType.inferType` for the expected type and `IndexedContext.scopeSymbols` for the in-scope pool, and checks candidates with the compiler's `<:<`. `analysis` holds the upickle model (`HoleContextResult`) and the bridge that adds project-type excerpts from the SemanticDB index; `mcp` adds the tool over the existing per-module backends, selecting `source`/disk/`pcFallback` exactly as `type_at_position` does.

**Tech Stack:** Scala 3.8.4, Mill 1.1.7, `scala3-presentation-compiler` 3.8.4 (already a `pc` dependency; brings `dotty.tools.dotc.*`, `dotty.tools.pc.*`, `scala.meta.internal.pc.EmptySymbolSearch`), `mtags-interfaces` 1.6.8 (`scala.meta.pc.reports.EmptyReportContext`), upickle/ujson, MUnit.

**Spec:** `/Users/viktorskalinins/IdeaProjects/my/scala-coding-guidelines/docs/superpowers/specs/2026-09-21-typed-hole-context-design.md`

## Global Constraints

- Wartremover is ON in `analysis` and `mcp` (no `var`, `null`, `asInstanceOf`, `throw`, `.head`, indexing — use `headOption`, `lift`, `zipWithIndex`); `pc` has `useWartremover = false` (Java/compiler interop) but keep the same discipline where it costs nothing.
- Every dotty-internal import (`dotty.tools.dotc.*`, `dotty.tools.pc.*`) stays inside the `pc` module. `analysis` and `mcp` see only plain Scala case classes and strings.
- Tool argument names and conventions copy `type_at_position`: `uri` (SemanticDB-form path relative to root), `line`/`character` 0-based, optional `source`; `pcFallback` fields via the existing `pcFallbackFields(uri)`; without a classpath the tool returns the existing "started without classpath" error.
- Result bounds: bindings ≤ 20, producers ≤ 15, givens ≤ 10, types ≤ 5; `omitted` carries the cut counts.
- Never a guessed candidate: with `confidence: unconstrained` the three candidate lists are empty.
- Types are rendered with the PC's `ShortenedTypePrinter` (short names) — the same printer family `type_at_position` output comes from.
- `./mill __.compile` regenerates SemanticDB; tests that boot a compiler live in `pc` or `McpPcSuite` (never in the stryker-filtered `McpSuite`); `munitTimeout` 120 s on PC suites.
- Commits end with `Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU`; no `Co-Authored-By`. Do not use `sbt`. Never `git checkout --`/`git restore`/`git stash`/`rm -rf` in an implementer session.

## Rulings that refine the spec (decided at planning)

1. **Producer matching.** Full type-parameter unification (`constrained` + `<:<` in a fresh typer state) is fragile from outside the typer. Producers whose result type is polymorphic are matched at class level — `result.classSymbol.derivesFrom(expected.classSymbol)` after `widen.dealias` — and carry `"match": "shape"`; monomorphic results that pass `<:<` carry `"match": "exact"`. Exact before shape in ordering. (Spec §1 JSON gains this one field on producers.)
2. **Docs example.** `ToolRunner` indexes compiled classes with no presentation compiler, so `hole_context` cannot be an mdoc-run example. The `docs/usage/tool-examples.md` entry shows a captured transcript from the live smoke, marked as such; the docs-golden suite gets no entry.
3. **Driver.** One `HoleContext` per `PresentationCompilerBackend`, created lazily on first use, closed with it. Its `InteractiveDriver` settings are `-classpath <cp>` + the backend's `options`; the PC's own driver is private and is not reused.
4. **Expected type source.** `dotty.tools.pc.InferCompletionType.inferType(path)` (public object in the PC jar) computes the type; `why` is derived by our own match on the same path head (`ValOrDefDef` → "result type of def <name>", `Apply` → "argument <i+1> of <fun>", `If`/`Match`/`Block`/`Typed` → "propagated from the enclosing <kind>"). When `inferType` returns `None` → `unconstrained`.

## File structure

- `pc/src/main/scala/com/github/mercurievv/scalasemantic/pc/HoleContext.scala` — driver, expected type, candidates (plain result case classes). (Tasks 1–2)
- `pc/src/test/scala/com/github/mercurievv/scalasemantic/pc/HoleContextSuite.scala` — compiler-backed tests. (Tasks 1–2)
- `pc/src/main/scala/com/github/mercurievv/scalasemantic/pc/PresentationCompilerBackend.scala` — `lazy val holes: HoleContext`, closed in `close()`. (Task 1)
- `analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/HoleContextResult.scala` — upickle model. (Task 3)
- `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/Analyzer.scala` — `holeContext(fileUri, code, docUri, offset)`. (Task 3)
- `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala` — the `hole_context` tool. (Task 4)
- `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpPcSuite.scala` — tool tests. (Task 4)
- `.claude/skills/typed-holes/SKILL.md`, `docs/usage/tool-examples.md`, `docs/reference/tools.md`, `README.md`, `chat-tool-usage.md`. (Task 5)
- dsh `scala-arhitect` profile pointer — separate repo, Task 5 step. (Task 5)

---

### Task 1: `HoleContext` — driver, hole location, expected type

**Files:**
- Create: `pc/src/main/scala/com/github/mercurievv/scalasemantic/pc/HoleContext.scala`
- Create: `pc/src/test/scala/com/github/mercurievv/scalasemantic/pc/HoleContextSuite.scala`
- Modify: `pc/src/main/scala/com/github/mercurievv/scalasemantic/pc/PresentationCompilerBackend.scala` (class body: add `holes`; `close()`)

**Interfaces:**
- Produces:

```scala
package com.github.mercurievv.scalasemantic.pc

object HoleContext:
  enum Confidence { case Exact, Unconstrained }
  final case class Expected(tpe: Option[String], confidence: Confidence, why: String)
  final case class Hole(kind: String /* "???" | "expression" */, text: String)
  final case class Binding(name: String, tpe: String, kind: String /* param|val|def */, from: String)
  final case class Producer(name: String, signature: String, from: String, matchKind: String /* exact|shape */)
  final case class Given(name: String, tpe: String, from: String)
  final case class TypeRef(symbol: String /* SemanticDB symbol */, displayName: String, signature: String)
  final case class Omitted(bindings: Int, producers: Int, givens: Int, types: Int)
  final case class Result(
      found: Boolean, why: Option[String], hole: Option[Hole], expected: Option[Expected],
      bindings: List[Binding], producers: List[Producer], givens: List[Given],
      types: List[TypeRef], omitted: Omitted)
  val maxBindings = 20; val maxProducers = 15; val maxGivens = 10; val maxTypes = 5

final class HoleContext(classpath: Seq[java.nio.file.Path], options: List[String]) extends AutoCloseable:
  /** `offset` is a 0-based character offset into `code`. */
  def at(fileUri: java.net.URI, code: String, offset: Int): HoleContext.Result
  def close(): Unit
```

  Task 1 fills `found`, `why`, `hole`, `expected` and returns empty candidate lists and zero `omitted`; Task 2 fills the rest.

- [ ] **Step 1: Write the failing tests**

```scala
package com.github.mercurievv.scalasemantic.pc

import HoleContext.*

class HoleContextSuite extends munit.FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration("120s")

  private val workspace = java.nio.file.Files.createTempDirectory("hole-ctx").nn
  private val file = workspace.resolve("Holes.scala").nn
  private val uri = file.toUri

  // The buffer under test: several hole shapes in one file. `@@` marks positions in the tests below
  // (stripped before the compiler sees the text), so a line/col never has to be counted by hand.
  private val marked =
    """package demo
      |
      |import cats.effect.IO
      |
      |trait Show[A]:
      |  def show(a: A): String
      |object Show:
      |  given Show[Int] = (a: Int) => a.toString
      |
      |object Holes:
      |  val label: String = "x"
      |  val count: Int = 3
      |  val names: List[String] = Nil
      |  def render(a: Int, s: String, xs: Seq[String]): String = @@1???
      |  def total(a: Int): Int = @@2???
      |  val untyped = @@3???
      |  def takes(n: Int, io: IO[Unit]): Unit = ()
      |  def call: Unit = takes(1, @@4???)
      |  def effect: IO[Unit] = @@5???
      |  def shown(using Show[Int]): String = summon[Show[Int]].show(@@6count)
      |  // a comment @@7 here
      |  def seqs: Seq[String] = @@8???
      |""".stripMargin

  private def marker(n: Int): (String, Int) =
    val tag = s"@@$n"
    val stripped = marked.replaceAll("@@\\d", "")
    val before = marked.indexOf(tag)
    val offset = marked.substring(0, before).replaceAll("@@\\d", "").length
    (stripped, offset)

  java.nio.file.Files.writeString(file, marked.replaceAll("@@\\d", ""))

  private val holes = new HoleContext(HoleContext.runtimeClasspath, Nil)
  override def afterAll(): Unit = holes.close()

  private def at(n: Int): Result =
    val (code, off) = marker(n)
    holes.at(uri, code, off)

  test("??? as a def result: exact expected type, why names the def") {
    val r = at(1)
    assert(r.found)
    assertEquals(r.hole.map(_.kind), Some("???"))
    assertEquals(r.expected.map(_.confidence), Some(Confidence.Exact))
    assertEquals(r.expected.flatMap(_.tpe), Some("String"))
    assertEquals(r.expected.map(_.why), Some("result type of def render"))
  }

  test("val without a declared type: unconstrained, no candidates") {
    val r = at(3)
    assertEquals(r.expected.map(_.confidence), Some(Confidence.Unconstrained))
    assertEquals(r.expected.flatMap(_.tpe), None)
    assert(r.expected.exists(_.why.contains("declare")))
    assertEquals(r.bindings, Nil); assertEquals(r.producers, Nil); assertEquals(r.givens, Nil)
  }

  test("??? as a method argument: the parameter's type") {
    val r = at(4)
    assertEquals(r.expected.flatMap(_.tpe), Some("IO[Unit]"))
    assertEquals(r.expected.map(_.why), Some("argument 2 of takes"))
  }

  test("an expression position that is not ???") {
    val r = at(6)
    assertEquals(r.hole.map(_.kind), Some("expression"))
    assertEquals(r.expected.flatMap(_.tpe), Some("Int"))
  }

  test("a position in a comment: not found") {
    val r = at(7)
    assert(!r.found)
    assert(r.why.exists(_.nonEmpty))
  }
```

The test classpath must hold cats-effect for `IO`: check `build.mill`'s `pc.test` deps; if cats-effect is not there, add `mvn"org.typelevel::cats-effect:3.6.3"` to `pc`'s `object test extends CommonTests { def mvnDeps = super.mvnDeps() ++ Seq(...) }` (test scope only). `HoleContext.runtimeClasspath` is the existing private `PresentationCompilerBackend.runtimeClasspath` made `private[pc]` and referenced from `HoleContext`'s companion (`def runtimeClasspath = PresentationCompilerBackend.runtimeClasspath`).

- [ ] **Step 2: Run to verify failure**

Run: `./mill pc.test.testOnly com.github.mercurievv.scalasemantic.pc.HoleContextSuite`
Expected: compile error — `HoleContext` not found.

- [ ] **Step 3: Implement**

```scala
package com.github.mercurievv.scalasemantic.pc

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols.defn
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.IndexedContext
import dotty.tools.pc.InferCompletionType
import dotty.tools.pc.printer.ShortenedTypePrinter
import dotty.tools.pc.printer.ShortenedTypePrinter.IncludeDefaultParam
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.pc.reports.EmptyReportContext
import scala.meta.pc.reports.ReportContext

import java.net.URI
import java.nio.file.Path

/** The typed-hole query (spec 2026-09-21-typed-hole-context-design §2): what type does a position
  * demand, and what in scope satisfies it. Runs its own dotty [[InteractiveDriver]] on the same
  * classpath as the module's presentation compiler — error-tolerant, so a buffer that does not yet
  * compile still answers. The expected type comes from the PC's own `InferCompletionType`, the
  * in-scope pool from its `IndexedContext`; every candidate is checked with the compiler's `<:<`,
  * never by comparing rendered strings. Not thread-safe; one instance per module backend.
  */
final class HoleContext(classpath: Seq[Path], options: List[String]) extends AutoCloseable:
  import HoleContext.*

  private given ReportContext = EmptyReportContext()
  private val driver = new InteractiveDriver(
    List("-classpath", classpath.map(_.toAbsolutePath.toString).mkString(java.io.File.pathSeparator)) ++ options
  )

  def at(fileUri: URI, code: String, offset: Int): Result =
    val source = SourceFile.virtual(fileUri, code)
    driver.run(fileUri, source)
    val ctx0 = driver.currentCtx
    driver.compilationUnits.get(fileUri) match
      case None => notFound("the compiler produced no unit for this file")
      case Some(unit) =>
        given Context = ctx0.fresh.setCompilationUnit(unit)
        val pos = SourcePosition(source, Span(offset))
        val path = Interactive.pathTo(unit.tpdTree, pos.span)
        path.headOption match
          case None => notFound("no tree at this position")
          case Some(head) if !isExpression(head) => notFound(s"position is not inside an expression (${head.getClass.getSimpleName})")
          case Some(head) =>
            val located = Interactive.contextOfPath(path)
            val indexed = IndexedContext(pos)(using located)
            val printer = ShortenedTypePrinter(EmptySymbolSearch, IncludeDefaultParam.Never)(using indexed)
            val hole = Hole(if isUndefined(head) then "???" else "expression", head.show.take(80))
            val expected = InferCompletionType.inferType(path) match
              case Some(tpe) if !tpe.isErroneous =>
                Expected(Some(printer.tpe(tpe.widenTermRefExpr)), Confidence.Exact, whyOf(path))
              case _ =>
                Expected(None, Confidence.Unconstrained,
                  "no type prescribed here — declare the val's type or the def's result type first")
            Result(found = true, why = None, hole = Some(hole), expected = Some(expected),
              bindings = Nil, producers = Nil, givens = Nil, types = Nil, omitted = Omitted(0, 0, 0, 0))

  private def notFound(why: String): Result =
    Result(false, Some(why), None, None, Nil, Nil, Nil, Nil, Omitted(0, 0, 0, 0))

  /** `???` is `Predef.???`, typed as `Nothing`; a `Select`/`Ident` of `defn.Predef_undefined`. */
  private def isUndefined(t: tpd.Tree)(using Context): Boolean = t match
    case tpd.Ident(_) | tpd.Select(_, _) => t.symbol == defn.Predef_undefined
    case tpd.Apply(fun, _)                => isUndefined(fun)
    case _                                => false

  private def isExpression(t: tpd.Tree)(using Context): Boolean = t match
    case _: tpd.TypeTree | _: tpd.Bind | _: tpd.Import | _: tpd.PackageDef | _: tpd.Template => false
    case d: tpd.DefTree                                                                    => false
    case _                                                                                 => t.isTerm

  /** Which enclosing construct prescribed the type: mirrors the cases `InferCompletionType.inferType` matches. */
  private def whyOf(path: List[tpd.Tree])(using Context): String =
    path.drop(1) match
      case (d: tpd.ValOrDefDef) :: _ if !d.tpt.tpe.isErroneous =>
        s"result type of ${if d.symbol.is(dotty.tools.dotc.core.Flags.Method) then "def" else "val"} ${d.name.show}"
      case (a: tpd.Apply) :: _ =>
        val idx = a.args.indexWhere(_.span.contains(path.headOption.map(_.span).getOrElse(Span(0))))
        s"argument ${idx + 1} of ${a.fun.symbol.name.show}"
      case tpd.NamedArg(name, _) :: (a: tpd.Apply) :: _ => s"argument $name of ${a.fun.symbol.name.show}"
      case (_: tpd.If) :: _     => "propagated from the enclosing if"
      case (_: tpd.CaseDef) :: _ => "propagated from the enclosing match"
      case (_: tpd.Block) :: rest => whyOf(path.headOption.toList ++ rest)
      case (_: tpd.Typed) :: _  => "the ascribed type"
      case _                    => "prescribed by the enclosing expression"

  def close(): Unit = () // InteractiveDriver holds no external resources; kept for symmetry with the backend

object HoleContext:
  enum Confidence:
    case Exact, Unconstrained
  final case class Expected(tpe: Option[String], confidence: Confidence, why: String)
  final case class Hole(kind: String, text: String)
  final case class Binding(name: String, tpe: String, kind: String, from: String)
  final case class Producer(name: String, signature: String, from: String, matchKind: String)
  final case class Given(name: String, tpe: String, from: String)
  final case class TypeRef(symbol: String, displayName: String, signature: String)
  final case class Omitted(bindings: Int, producers: Int, givens: Int, types: Int)
  final case class Result(
      found: Boolean,
      why: Option[String],
      hole: Option[Hole],
      expected: Option[Expected],
      bindings: List[Binding],
      producers: List[Producer],
      givens: List[Given],
      types: List[TypeRef],
      omitted: Omitted
  )
  val maxBindings = 20
  val maxProducers = 15
  val maxGivens = 10
  val maxTypes = 5

  def runtimeClasspath: Seq[Path] = PresentationCompilerBackend.runtimeClasspath
```

In `PresentationCompilerBackend`: change `private def runtimeClasspath` to `private[pc] def runtimeClasspath`; add to the class

```scala
  /** The typed-hole query on this module's classpath (spec 2026-09-21 §2); created on first use. */
  lazy val holes: HoleContext = new HoleContext(classpath, options)
```

and make `close()` also close `holes` if it was initialised (track with a `@volatile private var holesOpened = false` set inside a `def holesOrOpen` — or simpler, since `HoleContext.close()` is a no-op, call `holes.close()` unconditionally only when already evaluated: use `scala.util.Try(holes.close())`; an un-evaluated lazy val would construct a driver just to close it, so guard with a private `AtomicBoolean` flipped in the lazy val body). Adapt any dotty API name that differs in 3.8.4 (e.g. `Interactive.pathTo` overloads, `SourcePosition` constructor) and record the adaptation in the task report.

- [ ] **Step 4: Run the tests**

Run: `./mill pc.test.testOnly com.github.mercurievv.scalasemantic.pc.HoleContextSuite`
Expected: 5 tests pass. Also `./mill pc.test` (existing suite still green).

- [ ] **Step 5: Commit**

```bash
git add pc/
git commit -m "pc: HoleContext — driver, hole kind, expected type with confidence and why

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 2: Candidates — bindings, producers, givens, types, bounds

**Files:**
- Modify: `pc/src/main/scala/com/github/mercurievv/scalasemantic/pc/HoleContext.scala`
- Modify: `pc/src/test/scala/com/github/mercurievv/scalasemantic/pc/HoleContextSuite.scala`

**Interfaces:**
- Consumes: Task 1's `Result`, `printer`, `indexed`, `expected` type.
- Produces: the filled `bindings`/`producers`/`givens`/`types`/`omitted`; `TypeRef.symbol` in SemanticDB form (`cats/effect/IO#`) so `analysis` can look it up in the index.

- [ ] **Step 1: Failing tests** (append to the suite)

```scala
  test("bindings: conforming params and members listed, non-conforming excluded, kinds right") {
    val r = at(1) // expected String
    val names = r.bindings.map(b => b.name -> b.kind)
    assert(names.contains("s" -> "param"), names.toString)
    assert(names.contains("label" -> "val"), names.toString)
    assert(!r.bindings.exists(_.name == "a"), r.bindings.toString)      // Int does not conform to String
    assert(!r.bindings.exists(_.name == "count"), r.bindings.toString)
    assert(r.bindings.exists(b => b.name == "s" && b.from.contains("render")))
  }

  test("subtype binding accepted: List[String] fits Seq[String]") {
    val r = at(8)
    assert(r.bindings.exists(_.name == "names"), r.bindings.toString)
  }

  test("producers: companion members whose result fits, exact before shape, with signatures") {
    val r = at(5) // expected IO[Unit]
    val byName = r.producers.map(p => p.name -> p.matchKind).toMap
    assertEquals(byName.get("IO.unit"), Some("exact"))
    assertEquals(byName.get("IO.pure"), Some("shape"))
    assert(r.producers.find(_.name == "IO.pure").exists(_.signature.contains("IO[A]")))
    val firstShape = r.producers.indexWhere(_.matchKind == "shape")
    val lastExact = r.producers.lastIndexWhere(_.matchKind == "exact")
    assert(firstShape < 0 || lastExact < firstShape, r.producers.map(_.matchKind).toString)
  }

  test("givens: the instance for a typeclass expected type") {
    val (code, _) = marker(1)
    val off = code.indexOf("summon[Show[Int]]") + "summon[Show[Int]]".length - 1
    // position on the summon call: expected type of the whole expression is Show[Int]
    val r = holes.at(uri, code, off)
    assert(r.givens.exists(g => g.tpe == "Show[Int]"), r.givens.toString)
  }

  test("types: the expected type's class, with a SemanticDB symbol") {
    val r = at(5)
    assert(r.types.exists(_.symbol == "cats/effect/IO#"), r.types.toString)
    assert(r.types.exists(_.signature.contains("IO")))
  }

  test("bounds honoured with omitted counts") {
    val many = (1 to 30).map(i => s"  val s$i: String = \"\"").mkString("\n")
    val code = s"object Many:\n$many\n  def f: String = ???\n"
    val off = code.lastIndexOf("???")
    val r = holes.at(uri, code, off)
    assertEquals(r.bindings.size, HoleContext.maxBindings)
    assert(r.omitted.bindings >= 10, r.omitted.toString)
  }

  test("a type error elsewhere in the buffer does not stop the answer") {
    val (code, off) = marker(1)
    val broken = code.replace("val count: Int = 3", "val count: Int = \"oops\"")
    val r = holes.at(uri, broken, off)
    assert(r.found)
    assertEquals(r.expected.flatMap(_.tpe), Some("String"))
  }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mill pc.test.testOnly com.github.mercurievv.scalasemantic.pc.HoleContextSuite`
Expected: the 7 new tests fail (empty lists).

- [ ] **Step 3: Implement** — in `at`, replace the final `Result(...)` with `candidates(...)`, adding:

```scala
  import dotty.tools.dotc.core.Symbols.Symbol
  import dotty.tools.dotc.core.Flags
  import dotty.tools.dotc.core.Names.Name

  private def candidates(hole: Hole, expected: Expected, expectedTpe: Option[Type], indexed: IndexedContext,
                         printer: ShortenedTypePrinter, path: List[tpd.Tree])(using Context): Result =
    expectedTpe match
      case None => Result(true, None, Some(hole), Some(expected), Nil, Nil, Nil, Nil, Omitted(0, 0, 0, 0))
      case Some(want0) =>
        val want = want0.widenTermRefExpr.dealias
        val enclosing = path.collectFirst { case d: tpd.DefDef => d.symbol }.map(_.name.show).getOrElse("")
        val pool: List[Symbol] = indexed.scopeSymbols.distinct

        // --- bindings: values whose own type conforms -------------------------------------------
        def isValueLike(s: Symbol) = s.isTerm && !s.is(Flags.Package) && !s.is(Flags.Module) &&
          (!s.is(Flags.Method) || s.info.widen.isInstanceOf[dotty.tools.dotc.core.Types.ExprType] ||
            s.info.widen.paramInfoss.isEmpty)
        def kindOf(s: Symbol) = if s.is(Flags.Param) then "param" else if s.is(Flags.Method) then "def" else "val"
        def fromOf(s: Symbol) =
          if s.is(Flags.Param) || s.owner.is(Flags.Method) then s"enclosing def ${s.owner.name.show}"
          else if s.owner.is(Flags.Module) || s.owner.isClass then s"member of ${s.owner.name.show}"
          else "in scope"
        val allBindings = pool.filter(isValueLike).filter(s => s.info.widen.finalResultType <:< want)
          .map(s => Binding(s.name.show, printer.tpe(s.info.widen.finalResultType), kindOf(s), fromOf(s)))

        // --- producers: methods whose result type fits (exact) or is the same class (shape) --------
        val companions: List[(Symbol, String)] =
          (want.classSymbol :: want.argInfos.map(_.classSymbol)).filter(_.exists)
            .map(cls => cls.companionModule).filter(_.exists)
            .flatMap(m => m.info.decls.toList.filter(d => d.isTerm && d.is(Flags.Method) && !d.isConstructor)
              .map(d => d -> s"companion ${m.name.show.stripSuffix("$")}"))
        val methods: List[(Symbol, String)] =
          pool.filter(s => s.isTerm && s.is(Flags.Method) && s.info.widen.paramInfoss.nonEmpty).map(s => s -> fromOf(s))
        def resultOf(s: Symbol) = s.info.widen.finalResultType
        def matchKind(s: Symbol): Option[String] =
          val res = resultOf(s)
          if res.isErroneous || res.classSymbol == defn.NothingClass then None
          else if res <:< want then Some("exact")
          else if res.classSymbol.exists && want.classSymbol.exists && res.classSymbol.derivesFrom(want.classSymbol) then Some("shape")
          else None
        def nameOf(s: Symbol, from: String) =
          if from.startsWith("companion ") then s"${from.stripPrefix("companion ")}.${s.name.show}" else s.name.show
        val allProducers = (companions ++ methods).distinctBy(_._1)
          .flatMap((s, from) => matchKind(s).map(k => Producer(nameOf(s, from), printer.defaultMethodSignature(s, s.info.widen), from, k)))
          .sortBy(p => if p.matchKind == "exact" then 0 else 1)

        // --- givens: instances the compiler would pick for the expected type ------------------------
        val allGivens =
          if want.classSymbol.exists && (want.classSymbol.is(Flags.Trait) || want.classSymbol.isClass) && want.argInfos.nonEmpty then
            summon[Context].implicits.eligible(want).map(_.implicitRef.underlyingRef).map(ref =>
              Given(ref.symbol.name.show, printer.tpe(ref.underlying.widen.finalResultType), s"from ${ref.symbol.owner.name.show}"))
              .distinctBy(_.name)
          else Nil

        // --- types: the expected type's classes, in SemanticDB symbol form -------------------------
        val allTypes = (want.classSymbol :: want.argInfos.map(_.classSymbol)).filter(_.exists).distinct
          .map(cls => TypeRef(semanticdbSymbol(cls), cls.name.show, printer.hoverSymbol(cls, cls.info)))

        Result(true, None, Some(hole), Some(expected),
          allBindings.take(maxBindings), allProducers.take(maxProducers), allGivens.take(maxGivens), allTypes.take(maxTypes),
          Omitted((allBindings.size - maxBindings).max(0), (allProducers.size - maxProducers).max(0),
                  (allGivens.size - maxGivens).max(0), (allTypes.size - maxTypes).max(0)))

  /** SemanticDB symbol of a class: `a/b/C#`, nested `a/b/C#D#`, in an object `a/b/O.D#`. */
  private def semanticdbSymbol(cls: Symbol)(using Context): String =
    def owners(s: Symbol): String =
      if !s.exists || s.isRoot || s.isEffectiveRoot || s == defn.EmptyPackageClass then ""
      else if s.is(Flags.Package) then owners(s.owner) + s.name.show + "/"
      else if s.is(Flags.Module) then owners(s.owner) + s.name.show.stripSuffix("$") + "."
      else owners(s.owner) + s.name.show + "#"
    owners(cls)
```

Also change `at` to pass `expectedTpe` (the `Option[Type]` before printing) into `candidates`. Wire `printer.defaultMethodSignature`'s actual signature (check `ShortenedTypePrinter.scala:287` — `(gsym, gtpe, onlyMethodParams = false, …)`) and `hoverSymbol(sym, info)`; adapt if the 3.8.4 signatures differ, record it.

- [ ] **Step 4: Run the tests**

Run: `./mill pc.test.testOnly com.github.mercurievv.scalasemantic.pc.HoleContextSuite` → 12 tests pass. `./mill pc.test` green.

- [ ] **Step 5: Commit**

```bash
git add pc/
git commit -m "pc: HoleContext candidates — conforming bindings, exact/shape producers, eligible givens, types, bounds

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 3: `HoleContextResult` model and `Analyzer.holeContext`

**Files:**
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/HoleContextResult.scala`
- Modify: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/Analyzer.scala` (after `bufferOnly`)
- Test: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/HoleContextModelSuite.scala` (new, PC-free)

**Interfaces:**
- Consumes: `HoleContext.Result` (Task 2); `Analyzer.backendFor(docUri)`; the index's document lookup for project types.
- Produces:

```scala
package com.github.mercurievv.scalasemantic.model
import upickle.default.*

final case class HoleExpected(`type`: Option[String], confidence: String, why: String) derives ReadWriter
final case class HoleBinding(name: String, `type`: String, kind: String, from: String) derives ReadWriter
final case class HoleProducer(name: String, signature: String, from: String, `match`: String) derives ReadWriter
final case class HoleGiven(name: String, `type`: String, from: String) derives ReadWriter
final case class HoleType(symbol: String, source: String, signature: String, excerpt: Option[String]) derives ReadWriter
final case class HoleOmitted(bindings: Int, producers: Int, givens: Int, types: Int) derives ReadWriter
final case class HoleMarker(kind: String, text: String) derives ReadWriter
final case class HoleContextResult(
    found: Boolean, why: Option[String], hole: Option[HoleMarker], expected: Option[HoleExpected],
    bindings: List[HoleBinding], producers: List[HoleProducer], givens: List[HoleGiven],
    types: List[HoleType], omitted: HoleOmitted) derives ReadWriter

object HoleContextResult:
  /** Pure conversion from the pc result; `excerptOf` supplies a project type's definition excerpt (None → classpath). */
  def from(r: com.github.mercurievv.scalasemantic.pc.HoleContext.Result, excerptOf: String => Option[String]): HoleContextResult
```

```scala
// Analyzer
/** The typed-hole query for `code` at 0-based `offset`, on the module backend for `docUri`; None without a PC. */
def holeContext(fileUri: URI, code: String, docUri: String, offset: Int): Option[HoleContextResult]
```

- [ ] **Step 1: Failing test** (PC-free, pure conversion)

```scala
package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.pc.HoleContext as H

class HoleContextModelSuite extends munit.FunSuite:
  private val pcResult = H.Result(
    found = true, why = None, hole = Some(H.Hole("???", "???")),
    expected = Some(H.Expected(Some("IO[Unit]"), H.Confidence.Exact, "result type of def effect")),
    bindings = List(H.Binding("release", "IO[Unit]", "param", "enclosing def run")),
    producers = List(H.Producer("IO.unit", "IO[Unit]", "companion IO", "exact")),
    givens = Nil,
    types = List(H.TypeRef("cats/effect/IO#", "IO", "sealed abstract class IO[+A]"), H.TypeRef("demo/Show#", "Show", "trait Show[A]")),
    omitted = H.Omitted(0, 3, 0, 0))

  test("conversion keeps every field and lower-cases the confidence") {
    val m = HoleContextResult.from(pcResult, s => if s == "demo/Show#" then Some("trait Show[A]:\n  def show(a: A): String") else None)
    assertEquals(m.expected.map(_.confidence), Some("exact"))
    assertEquals(m.producers.map(_.`match`), List("exact"))
    assertEquals(m.omitted.producers, 3)
  }

  test("types: project types get source=project and an excerpt, classpath types source=classpath") {
    val m = HoleContextResult.from(pcResult, s => if s == "demo/Show#" then Some("trait Show[A]") else None)
    assertEquals(m.types.map(t => t.symbol -> t.source), List("cats/effect/IO#" -> "classpath", "demo/Show#" -> "project"))
    assertEquals(m.types.find(_.symbol == "demo/Show#").flatMap(_.excerpt), Some("trait Show[A]"))
  }

  test("JSON shape: keys named as the spec, confidence unconstrained serialises with empty lists") {
    val m = HoleContextResult.from(pcResult.copy(expected = Some(H.Expected(None, H.Confidence.Unconstrained, "no type prescribed here")), bindings = Nil, producers = Nil), _ => None)
    val json = ujson.read(upickle.default.write(m))
    assertEquals(json("expected")("confidence").str, "unconstrained")
    assertEquals(json("bindings").arr.size, 0)
    assert(json.obj.keySet.contains("omitted"))
  }
```

- [ ] **Step 2: Run to verify failure** — `./mill analysis.test.testOnly com.github.mercurievv.scalasemantic.analysis.HoleContextModelSuite` → compile error.

- [ ] **Step 3: Implement** the model file with the case classes above and

```scala
object HoleContextResult:
  def from(r: HoleContext.Result, excerptOf: String => Option[String]): HoleContextResult =
    HoleContextResult(
      found = r.found, why = r.why,
      hole = r.hole.map(h => HoleMarker(h.kind, h.text)),
      expected = r.expected.map(e => HoleExpected(e.tpe, e.confidence.toString.toLowerCase, e.why)),
      bindings = r.bindings.map(b => HoleBinding(b.name, b.tpe, b.kind, b.from)),
      producers = r.producers.map(p => HoleProducer(p.name, p.signature, p.from, p.matchKind)),
      givens = r.givens.map(g => HoleGiven(g.name, g.tpe, g.from)),
      types = r.types.map { t =>
        val ex = excerptOf(t.symbol)
        HoleType(t.symbol, if ex.isDefined then "project" else "classpath", t.signature, ex)
      },
      omitted = HoleOmitted(r.omitted.bindings, r.omitted.producers, r.omitted.givens, r.omitted.types))
```

and in `Analyzer`:

```scala
  /** The typed-hole query (spec 2026-09-21 §1) for the live `code` of the file at `fileUri` (keyed
    * `docUri`) at a 0-based character `offset`, answered by the module backend's [[HoleContext]].
    * Project-owned types (a document in this index defines them) carry an excerpt of their
    * definition's first lines; classpath types the signature alone. `None` without a PC backend.
    */
  def holeContext(fileUri: URI, code: String, docUri: String, offset: Int): Option[HoleContextResult] =
    backendFor(docUri).map { backend =>
      val r = backend.holes.at(fileUri, code, offset)
      HoleContextResult.from(r, sym => definitionExcerpt(sym, maxLines = 12))
    }
```

`definitionExcerpt(symbol, maxLines)` — look for the existing helper that `symbol_source` uses to read a definition's lines from the index (grep `symbol_source` handler in `McpTools.scala` → the `Analyzer`/`AnalyzerHelpers` method it calls, e.g. a `definitionRange`/`renderRange` pair). If a suitable analyzer-level function exists, call it and take the first 12 lines; otherwise add `definitionExcerpt` in `AnalyzerHelpers` as: find `index.info(symbol)`'s definition occurrence (`SymbolOccurrence.Role.DEFINITION`) in its document, read the on-disk file (`index` knows the uri; root-relative → the tool passes an absolute root — thread `root: Path` through if needed), return the definition's start line through min(end, start+12) joined by `\n`; `None` when the symbol has no document in the index.

- [ ] **Step 4: Run** — `./mill analysis.test.testOnly …HoleContextModelSuite` → 3 pass; `./mill analysis.test` green.

- [ ] **Step 5: Commit**

```bash
git add analysis/
git commit -m "analysis: HoleContextResult model and Analyzer.holeContext with project-type excerpts

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 4: The `hole_context` MCP tool

**Files:**
- Modify: `mcp/src/main/scala/com/github/mercurievv/scalasemantic/mcp/McpTools.scala` (add after the `type_at_position` `toolDef`)
- Modify: `mcp/src/test/scala/com/github/mercurievv/scalasemantic/mcp/McpPcSuite.scala` (new tests)
- Modify: `regressions/unit-tests.json` (register the new test names, following how `source_ranges` tests are listed)

**Interfaces:**
- Consumes: `Analyzer.holeContext(fileUri, code, docUri, offset)`; `argUri`, `argPosition`, `readFileText`, `pcFallbackFields`, `jobj`, `error`.
- Produces: tool `hole_context` with args `uri` (required), `line`, `character` (required, 0-based), `source` (optional). Result = `upickle.default.writeJs(HoleContextResult)` plus `pcFallback` fields when the disk file was used.

- [ ] **Step 1: Failing tests** (append to `McpPcSuite`, reusing its `req`/`currentRuntimeClasspath` helpers and the `useCurrentJvm` pattern of the `extract_method_plan` test)

```scala
  test("hole_context: ??? as a def result answers expected type, bindings and producers") {
    val root = java.nio.file.Files.createTempDirectory("mcp-hole").nn
    val file = root.resolve("Holes.scala").nn
    val source =
      """package demo
        |object Holes:
        |  val label: String = "x"
        |  def render(a: Int, s: String): String = ???
        |""".stripMargin
    java.nio.file.Files.writeString(file, source)
    PresentationCompilerBackend.useCurrentJvm(workspace = Some(root)) { backend =>
      val tools = McpTools.all(Analyzer(new SemanticIndex(Vector.empty), Some(backend)), root)
      val line = 3; val character = source.linesIterator.toList(3).indexOf("???")
      val resp = Mcp.handle(req("tools/call", ujson.Obj("name" -> "hole_context",
        "arguments" -> ujson.Obj("uri" -> "Holes.scala", "line" -> line, "character" -> character, "source" -> source))), tools)
      val text = resp("result")("content")(0)("text").str
      val json = ujson.read(text)
      assertEquals(json("expected")("type").str, "String")
      assertEquals(json("expected")("confidence").str, "exact")
      assert(json("bindings").arr.exists(_("name").str == "s"), text)
      assert(json("bindings").arr.exists(_("name").str == "label"), text)
      assert(json.obj.keySet.contains("omitted"))
    }
  }

  test("hole_context: without source reads the disk file and marks pcFallback") {
    // same root/file as above; omit `source`
    // … build as above, call without "source"
    // assert json("pcFallback").bool == true and json("expected")("type").str == "String"
  }

  test("hole_context: a position in a comment is found=false with a why") {
    // source with a comment line; assert json("found").bool == false && json("why").str.nonEmpty
  }

  test("hole_context: without a classpath the tool returns the no-classpath error") {
    val root = java.nio.file.Files.createTempDirectory("mcp-hole-nocp").nn
    val tools = McpTools.all(Analyzer(new SemanticIndex(Vector.empty)), root)
    val resp = Mcp.handle(req("tools/call", ujson.Obj("name" -> "hole_context",
      "arguments" -> ujson.Obj("uri" -> "X.scala", "line" -> 0, "character" -> 0, "source" -> "object X"))), tools)
    val text = resp("result")("content")(0)("text").str
    assert(text.toLowerCase.contains("classpath"), text)
  }
```

Fill the two abbreviated tests fully (copy the first test's scaffolding; they are separate tests, not shared state). Look at how `type_at_position` reports the no-classpath case when `source` is given and no backend exists (`az.bufferOnly` → `None` → falls back to the index) — for `hole_context` there is no index fallback, so return `error("hole_context needs the presentation compiler: start the server with a classpath (see README → classpath)")` when `holeContext` returns `None`; find the exact wording used by the existing no-classpath message (grep `classpath` in `McpTools.scala`) and reuse it.

- [ ] **Step 2: Run to verify failure** — `./mill mcp.test.testOnly com.github.mercurievv.scalasemantic.mcp.McpPcSuite` → the new tests fail (`unknown tool: hole_context`).

- [ ] **Step 3: Implement**

```scala
      toolDef(
        tool(
          "hole_context",
          "Typed-hole context: the type the compiler expects at a 0-based (line, character) — a `???` " +
            "or any expression — with `confidence` (`exact` when the enclosing code prescribes it, " +
            "`unconstrained` when nothing does, then no candidates are guessed), and what in reach " +
            "satisfies it: `bindings` (in-scope values that conform), `producers` (methods/companion " +
            "members whose result fits — `match` exact or shape), `givens` (instances the compiler would " +
            "pick), `types` (the expected type's definitions; project types carry an excerpt). Pass " +
            "`source` with the file's CURRENT text (uncompiled is fine); without it the on-disk file is " +
            "read (marked `pcFallback`). Needs the server started with a classpath. Use it one hole at a " +
            "time — see the typed-holes skill.",
          List(
            ("uri", "string", "document uri as it appears in SemanticDB (path relative to project root)"),
            ("line", "integer", "0-based line"),
            ("character", "integer", "0-based column"),
            ("source", "string", "current full text of the file at `uri`")
          ),
          List("uri", "line", "character")
        ) { a =>
          val uri = argUri(a, "uri")
          val file = root.resolve(uri.value)
          val (code, fromDisk) = a.obj.get("source").map(_.str) match
            case Some(src) => (Some(src), false)
            case None      => (readFileText(file), true)
          code match
            case None => notFound(s"no source: pass `source` or make ${uri.value} exist on disk")
            case Some(text) =>
              val pos = argPosition(a, "line", "character")
              val offset = offsetOf(text, pos.line, pos.character)
              az.holeContext(file.toUri, text, uri.value, offset) match
                case None => error(noClasspathMessage("hole_context"))
                case Some(r) =>
                  val base = upickle.default.writeJs(r)
                  if fromDisk then ujson.Obj.from(base.obj.toSeq ++ pcFallbackFields(uri.value)) else base
        }
      ),
```

`offsetOf(text, line, character)`: sum of the lengths (+1 for `\n`) of the first `line` lines plus `character`; check whether `McpTools`/`AnalyzerHelpers` already has such a helper (grep `offset`) and reuse it. `noClasspathMessage` — the existing message text; extract into a small helper if it is currently inlined in more than one place, otherwise inline the same string. `argPosition` returns the existing position type — read its field names.

- [ ] **Step 4: Run** — `./mill mcp.test.testOnly …McpPcSuite` → all pass; `./mill mcp.test` green; add the new test names to `regressions/unit-tests.json` in the same format as the neighbours (`ClassName.test name`).

- [ ] **Step 5: Commit**

```bash
git add mcp/ regressions/unit-tests.json
git commit -m "mcp: hole_context tool — expected type, bindings, producers, givens, types at a position

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

### Task 5: Skill, docs, dsh pointer, live smoke

**Files:**
- Create: `.claude/skills/typed-holes/SKILL.md`
- Modify: `docs/usage/tool-examples.md` (Compiler view table + a `### hole_context` section), `docs/reference/tools.md` (table row), `README.md:34` (tool list), `chat-tool-usage.md` (list entry near `type_at_position`)
- Modify (other repo): the dsh `scala-arhitect` profile — locate with `grep -rn 'ScalaSemantic\|scala-semantic' ~/.dsh ~/IdeaProjects/dsh 2>/dev/null` (the profile directory the user showed earlier holds the harness's Scala guidance); add one line under its ScalaSemantic tool guidance.

- [ ] **Step 1: Write the skill**

```markdown
---
name: typed-holes
description: Use when writing a new def/val body or any function longer than a one-liner in a project served by ScalaSemantic — write the signature with ??? first, compile, then fill each hole with hole_context
---

# Typed holes: skeleton first, fill one hole at a time

The compiler knows what type a position needs and what in scope satisfies it. Ask it at each hole
instead of guessing an API from memory — hallucinated members and wrong types come from definitions
that are neither in the file nor in training data; `hole_context` puts exactly those in front of you.

## Procedure

1. **Skeleton first.** Write the signature(s) with `???` bodies. Steps inside a body become local
   `def step: T = ???` with their types declared. Compile (`<module>.compile`): signature errors
   surface before any body exists.
2. **One hole at a time.** Call `hole_context(uri, line, character, source)` on the first `???`
   (pass the buffer's current text as `source`). Read `expected`:
   - `confidence: exact` → choose from `bindings` (already the right type), `producers` (build it:
     `match: exact` needs only the arguments shown; `match: shape` also needs the type argument to
     line up), `givens` (instances you may `summon`/pass). Read `types` before using a member you
     have not seen in this file.
   - `confidence: unconstrained` → nothing prescribes the type. Declare it (the val's type, the
     def's result type) and ask again.
   - a body longer than about three expressions → split it into further `???` sub-holes with
     declared types rather than writing it in one go.
3. **Fill, compile, repeat.** After each fill run `<module>.compile` before the next hole. A red
   compile is fixed at that hole — never by rewriting other holes.

## Red flags

| Thought | Reality |
|---|---|
| "I know this API" | A member not in this file is exactly where hallucinations live. Ask `hole_context`, read `types`. |
| "I'll fill all the holes, then compile" | One hole, one compile. Errors then have one cause. |
| "A cast/`null` will satisfy the expected type" | Never `asInstanceOf`/`null` to satisfy a type. Build the value from `producers` or add a binding. |
| "The candidate lists are empty, so anything goes" | With `confidence: exact` and empty lists the value has to be constructed: ask on a smaller sub-hole or add a parameter/import. |
| "`unconstrained` means I decide the type silently" | Declare it in the source first; the next answer is then `exact`. |
```

- [ ] **Step 2: Docs**

- `docs/usage/tool-examples.md`: add `| \`hole_context\` | Expected type at a hole and what in scope satisfies it |` to the Compiler view table, and a `### hole_context` section: "**What it tells you:** …" one paragraph, then a fenced JSON block with the transcript captured in Step 4 (trimmed to ≤ 25 lines), prefaced by: *This example is a captured run (the tool needs a presentation compiler, which the docs build does not start).*
- `docs/reference/tools.md`: table row `| \`hole_context\` | Expected type at a position (with confidence) and the in-scope bindings, producers and givens that satisfy it. |`.
- `README.md` line 34: add `` `hole_context` `` to the list; `chat-tool-usage.md`: add `- \`hole_context\`` next to `type_at_position` with a half-line note "one hole at a time — see `.claude/skills/typed-holes`".

- [ ] **Step 3: dsh profile pointer** — in the `scala-arhitect` profile's ScalaSemantic guidance add: `- New bodies: skeleton with ??? first, then ScalaSemantic's typed-holes skill (hole_context) one hole at a time.` Commit in that repo with the same trailer.

- [ ] **Step 4: Live smoke** — start the server against skreeep2 with its classpath (see README for `SCALASEMANTIC_CLASSPATH`/`--classpath`; the `.scala-semantic/classpath-*.json` may already exist under `~/IdeaProjects/skreeep2`), write a scratch file `core/src/com/github/mercurievv/skreeep2/DshHole.scala` with `def eff(release: IO[Unit]): IO[Unit] = ???`, call `hole_context` via `ToolCli` (see `mcp/…/ToolCli.scala` for the CLI shape) on the `???`, paste the JSON into the task report and into the docs example, then `rm` the scratch file and confirm `git -C ~/IdeaProjects/skreeep2 status` shows no `core/` change.

- [ ] **Step 5: Verify and commit**

Run: `./mill __.compile && ./mill __.test` (all modules green) and `./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll` (or the repo's `checkFormatAll` task).

```bash
git add .claude/skills/typed-holes/SKILL.md docs/usage/tool-examples.md docs/reference/tools.md README.md chat-tool-usage.md
git commit -m "docs+skill: typed-holes skill; hole_context documented with a captured example

Claude-Session: https://claude.ai/code/session_01WeEP7mpZmcZRnZgxUDUJsU"
```

---

## Self-review

- Spec coverage: §1 contract → Tasks 3–4 (model + tool, `pcFallback`, no-classpath error, bounds/omitted from Task 2); §2 engine → Tasks 1–2 (driver, expected type via `InferCompletionType`, bindings, producers, givens, types; `pc`-only dotty imports); §3 skill + docs + dsh pointer → Task 5; §4 tests → Task 1 (exact/unconstrained/argument/expression/comment), Task 2 (subtype, non-conforming, generic producer, given, bounds, error elsewhere), Task 4 (JSON shape, source vs disk, pcFallback, no-classpath); docs-golden replaced by a captured example per ruling 2.
- Placeholders: Task 4 marks two tests "build as above" — the step text instructs to write them fully; the `definitionExcerpt` helper in Task 3 is specified by behaviour with the fallback implementation described. Dotty API names are given as of the 3.8.4 sources read at planning (`InferCompletionType.inferType(path)`, `IndexedContext(pos).scopeSymbols`, `ShortenedTypePrinter(search, IncludeDefaultParam)`, `Interactive.pathTo`, `Interactive.contextOfPath`, `ctx.implicits.eligible`); implementers adapt overloads and record it.
- Type consistency: `HoleContext.Result` fields ↔ `HoleContextResult.from`; `Producer.matchKind` ↔ JSON `match`; `Confidence.Exact/Unconstrained` ↔ `"exact"/"unconstrained"`; `Analyzer.holeContext(fileUri: URI, code: String, docUri: String, offset: Int)` ↔ the tool's call; `PresentationCompilerBackend.holes` ↔ `backend.holes.at`.
