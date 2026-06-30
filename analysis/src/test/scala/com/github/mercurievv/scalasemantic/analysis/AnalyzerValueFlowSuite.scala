package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Deterministic tests for [[Analyzer.valueFlow]] over a hand-built in-memory index encoding a
  * small flow: `val a = source(); val b = a; sink(b)` with `def sink(n: Int): Int = n`. The layout
  * exercises the three core relations — assigned_to (a -> b), passed_as_arg into a renamed
  * parameter (b -> n), and a returned value (n -> function_result terminal) — plus depth limiting.
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
