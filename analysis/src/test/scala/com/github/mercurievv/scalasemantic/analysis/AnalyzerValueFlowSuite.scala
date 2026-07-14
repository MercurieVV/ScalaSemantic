package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Deterministic tests for [[Analyzer.valueFlow]] over a hand-built in-memory index encoding a
  * small flow: `val a = source(); val b = a; sink(b)` with `def sink(n: Int): Int = n`. The layout
  * exercises the three core relations — assigned_to (a -> b), passed_as_arg into a renamed
  * parameter (b -> n), and a returned value (n -> function_result terminal) — plus depth limiting.
  * A second fixture covers the `passed_as_implicit` relation and `implicit_boundary` terminal for a
  * value flowing into an implicit/`using` parameter, via a context-bound method and a
  * `using`-clause method that desugar to the identical SemanticDB shape.
  */
class AnalyzerValueFlowSuite extends munit.FunSuite:

  private val P = "com/x/"
  private val IntT = s.TypeRef(s.Type.Empty, "scala/Int#", Nil)

  private val aSym = "local0"
  private val bSym = "local1"
  private val sinkSym = s"${P}Run.sink()."
  private val nSym = s"${P}Run.sink().(n)"
  private val sourceSym = s"${P}Run.source()."
  private val runSym = s"${P}Run.run()."

  import s.SymbolOccurrence.Role.{DEFINITION, REFERENCE}

  private def info(
      symbol: String,
      kind: s.SymbolInformation.Kind,
      display: String,
      sig: s.Signature
  ): s.SymbolInformation =
    s.SymbolInformation(symbol = symbol, kind = kind, displayName = display, signature = sig)

  private def occ(symbol: String, role: s.SymbolOccurrence.Role, line: Int, col: Int, end: Int) =
    s.SymbolOccurrence(Some(s.Range(line, col, line, end)), symbol, role)

  private val symbols = Vector(
    info(runSym, s.SymbolInformation.Kind.METHOD, "run", s.MethodSignature(None, Nil, IntT)),
    info(
      sourceSym,
      s.SymbolInformation.Kind.METHOD,
      "source",
      s.MethodSignature(None, Nil, IntT)
    ),
    info(
      sinkSym,
      s.SymbolInformation.Kind.METHOD,
      "sink",
      s.MethodSignature(None, List(s.Scope(symlinks = List(nSym))), IntT)
    ),
    info(nSym, s.SymbolInformation.Kind.PARAMETER, "n", s.ValueSignature(IntT)),
    info(aSym, s.SymbolInformation.Kind.LOCAL, "a", s.ValueSignature(IntT)),
    info(bSym, s.SymbolInformation.Kind.LOCAL, "b", s.ValueSignature(IntT))
  )

  // Source layout the occurrence columns encode:
  //   0: def run(): Int =
  //   1:   val a = source()
  //   2:   val b = a
  //   3:   sink(b)
  //   5: def sink(n: Int): Int = n
  //   7: def source(): Int = 42
  private val occurrences = Vector(
    occ(runSym, DEFINITION, 0, 6, 9),
    occ(aSym, DEFINITION, 1, 6, 7),
    occ(sourceSym, REFERENCE, 1, 10, 16),
    occ(bSym, DEFINITION, 2, 6, 7),
    occ(aSym, REFERENCE, 2, 10, 11),
    occ(sinkSym, REFERENCE, 3, 4, 8),
    occ(bSym, REFERENCE, 3, 9, 10),
    occ(sinkSym, DEFINITION, 5, 8, 12),
    occ(nSym, DEFINITION, 5, 13, 14),
    occ(nSym, REFERENCE, 5, 24, 25),
    occ(sourceSym, DEFINITION, 7, 8, 14)
  )

  private val az = Analyzer(
    SemanticIndex(
      Vector(s.TextDocument(uri = "run.scala", symbols = symbols, occurrences = occurrences))
    )
  )

  private def sym(v: String): SemanticDbSymbol =
    SemanticDbSymbol.from(v).fold(e => fail(e), identity)

  private def depth(v: Int): PositiveInt =
    PositiveInt.from(v, "depth").fold(e => fail(e), identity)

  test("traces value across assignment and into a renamed parameter, ending in a return"):
    val r = az.valueFlow(sym(aSym), depth(5), stopOnTypeWidening = true)

    assertEquals(r.root.symbol, aSym)
    assertEquals(r.nodes.map(_.symbol).toSet, Set(aSym, bSym, nSym))

    val edges = r.edges.map(e => (e.from, e.to, e.relation)).toSet
    assert(edges.contains((aSym, bSym, "assigned_to")), s"missing assigned_to edge: $edges")
    assert(edges.contains((bSym, nSym, "passed_as_arg")), s"missing passed_as_arg edge: $edges")

    val terminals = r.stoppedAt.map(t => (t.symbol, t.classification)).toSet
    assert(
      terminals.contains((nSym, "function_result")),
      s"expected n to terminate as function_result: $terminals"
    )

  test("depth limit truncates expansion and reports the cut node"):
    val r = az.valueFlow(sym(aSym), depth(1), stopOnTypeWidening = true)

    assertEquals(r.nodes.map(_.symbol).toSet, Set(aSym, bSym))
    assertEquals(r.edges.map(e => (e.from, e.to)).toSet, Set((aSym, bSym)))
    assertEquals(
      r.truncatedAt.map(t => (t.symbol, t.classification)).toSet,
      Set((bSym, "depth_limit"))
    )

  // --- implicit-argument flow -------------------------------------------------------------
  //
  // A context bound (`def p[A: Show](a: A)`) and a `using` clause (`def q(a: A)(using
  // Show[A])`) desugar to the SAME SemanticDB shape — a trailing parameter list holding a
  // PARAMETER flagged IMPLICIT — so one fixture with both methods proves value_flow follows
  // either surface identically into `passed_as_implicit` / `implicit_boundary`.

  private val ShowIntT = s.TypeRef(s.Type.Empty, "com/x/Show#", List(IntT))
  private val UnitT = s.TypeRef(s.Type.Empty, "scala/Unit#", Nil)

  private val evSym = "local2"
  private val xSym = "local3"
  private val pSym = s"${P}Run2.p()."
  private val aParamPSym = s"${P}Run2.p().(a)"
  private val evParamPSym = s"${P}Run2.p().(ev)"
  private val qSym = s"${P}Run2.q()."
  private val aParamQSym = s"${P}Run2.q().(a)"
  private val evParamQSym = s"${P}Run2.q().(ev)"

  private def implicitParam(
      symbol: String,
      display: String,
      tpe: s.Type
  ): s.SymbolInformation =
    s.SymbolInformation(
      symbol = symbol,
      kind = s.SymbolInformation.Kind.PARAMETER,
      displayName = display,
      signature = s.ValueSignature(tpe),
      properties = s.SymbolInformation.Property.IMPLICIT.value
    )

  private val implicitSymbols = Vector(
    info(evSym, s.SymbolInformation.Kind.LOCAL, "ev", s.ValueSignature(ShowIntT)),
    info(xSym, s.SymbolInformation.Kind.LOCAL, "x", s.ValueSignature(IntT)),
    info(
      pSym,
      s.SymbolInformation.Kind.METHOD,
      "p",
      s.MethodSignature(
        None,
        List(s.Scope(symlinks = List(aParamPSym)), s.Scope(symlinks = List(evParamPSym))),
        UnitT
      )
    ),
    info(aParamPSym, s.SymbolInformation.Kind.PARAMETER, "a", s.ValueSignature(IntT)),
    implicitParam(evParamPSym, "ev", ShowIntT),
    info(
      qSym,
      s.SymbolInformation.Kind.METHOD,
      "q",
      s.MethodSignature(
        None,
        List(s.Scope(symlinks = List(aParamQSym)), s.Scope(symlinks = List(evParamQSym))),
        UnitT
      )
    ),
    info(aParamQSym, s.SymbolInformation.Kind.PARAMETER, "a", s.ValueSignature(IntT)),
    implicitParam(evParamQSym, "ev", ShowIntT)
  )

  // Source layout the occurrence columns encode:
  //   1:   val ev = ...
  //   2:   val x = 1
  //   3:   p(x)(using ev)   // `def p[A: Show](a: A): Unit` — context bound desugars to this
  //   4:   q(x)(using ev)   // `def q(a: A)(using Show[A]): Unit` — the identical shape
  private val implicitOccurrences = Vector(
    occ(evSym, DEFINITION, 1, 6, 8),
    occ(xSym, DEFINITION, 2, 6, 7),
    occ(pSym, REFERENCE, 3, 2, 3),
    occ(xSym, REFERENCE, 3, 4, 5),
    occ(evSym, REFERENCE, 3, 13, 15),
    occ(qSym, REFERENCE, 4, 2, 3),
    occ(xSym, REFERENCE, 4, 4, 5),
    occ(evSym, REFERENCE, 4, 13, 15)
  )

  private val azImplicit = Analyzer(
    SemanticIndex(
      Vector(
        s.TextDocument(
          uri = "run2.scala",
          symbols = implicitSymbols,
          occurrences = implicitOccurrences
        )
      )
    )
  )

  test("follows a value passed as an implicit/using arg — context bound and using-clause alike"):
    val r = azImplicit.valueFlow(sym(evSym), depth(5), stopOnTypeWidening = true)

    val edges = r.edges.map(e => (e.from, e.to, e.relation)).toSet
    assert(
      edges.contains((evSym, evParamPSym, "passed_as_implicit")),
      s"missing passed_as_implicit edge into context-bound param: $edges"
    )
    assert(
      edges.contains((evSym, evParamQSym, "passed_as_implicit")),
      s"missing passed_as_implicit edge into using param: $edges"
    )

    val terminals = r.stoppedAt.map(t => (t.symbol, t.classification)).toSet
    assert(
      terminals.contains((evParamPSym, "implicit_boundary")),
      s"expected context-bound evidence param to terminate as implicit_boundary: $terminals"
    )
    assert(
      terminals.contains((evParamQSym, "implicit_boundary")),
      s"expected using-clause evidence param to terminate as implicit_boundary: $terminals"
    )
