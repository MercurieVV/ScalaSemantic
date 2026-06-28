package com.github.mercurievv.scalasemantic.analysis.graph

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Deterministic tests for the structure-metrics graph package, driven against hand-built graphs
  * and a small in-memory project index (filter-safe, unlike AnalyzerStructureSuite which dogfoods
  * the live index). Covers the pure algorithms in [[GraphMetrics]] directly, then the
  * index-to-graph extraction in [[DependencyGraphs]] and the rollup in [[StructureMetrics]].
  */
class StructureMetricsSuite extends munit.FunSuite:

  // ============================ GraphMetrics (pure) ============================

  test("coupling counts in-project fan-in (Ca) and fan-out (Ce)"):
    // a -> b, a -> c, b -> c, plus an edge to an out-of-set node. Ca counts only in-set fan-in;
    // Ce is the raw out-degree (so the out-of-set 'ext' still counts toward a's efferent).
    val g = Map("a" -> Set("b", "c", "ext"), "b" -> Set("c"))
    val nodes = Set("a", "b", "c")
    val cp = GraphMetrics.coupling(nodes, g)
    assertEquals(cp("a"), (0, 3), "a: no in, three out (b, c, ext)")
    assertEquals(cp("b"), (1, 1), "b: in from a, out to c")
    assertEquals(cp("c"), (2, 0), "c: in from a and b, no out")

  test("instability is Ce/(Ca+Ce), 0 for an isolated node"):
    assertEquals(GraphMetrics.instability(0, 0), 0.0)
    assertEquals(GraphMetrics.instability(3, 1), 0.25)
    assertEquals(GraphMetrics.instability(0, 5), 1.0)

  test("stronglyConnectedComponents groups a cycle, isolates acyclic nodes"):
    val g = Map("a" -> Set("b"), "b" -> Set("a"), "c" -> Set("a"))
    val comps = GraphMetrics.stronglyConnectedComponents(Set("a", "b", "c"), g).map(_.toList.sorted)
    assertEquals(comps.size, 2, s"exactly two components: $comps")
    assert(comps.contains(List("a", "b")), s"cycle {a,b} expected: $comps")
    assert(comps.contains(List("c")), s"singleton {c} expected: $comps")
    assertEquals(comps.flatten.sorted, List("a", "b", "c"))

  test("layers: longest dependency chain depth, cycles condensed to one level"):
    // c -> b -> a (a is the foundation). plus a self-contained cycle x<->y.
    val g = Map("c" -> Set("b"), "b" -> Set("a"), "x" -> Set("y"), "y" -> Set("x"))
    val l = GraphMetrics.layers(Set("a", "b", "c", "x", "y"), g)
    assertEquals(l("a"), 0, "foundation")
    assertEquals(l("b"), 1)
    assertEquals(l("c"), 2)
    assertEquals(l("x"), l("y"), "cycle members share a level")

  test("pageRank flows toward depended-on foundations"):
    val g = Map("b" -> Set("a"), "c" -> Set("a"))
    val pr = GraphMetrics.pageRank(Set("a", "b", "c"), g)
    assert(pr("a") > pr("b"), s"a (depended on by b,c) outranks b: $pr")
    assert(pr("a") > pr("c"), s"a outranks c: $pr")
    assertEquals(GraphMetrics.pageRank(Set.empty, Map.empty), Map.empty)

  // ====================== DependencyGraphs / StructureMetrics ==================

  private def tref(sym: String): s.Type = s.TypeRef(s.Type.Empty, sym, Nil)
  private val ObjectT = tref("java/lang/Object#")
  private val IntT = tref("scala/Int#")
  private val IMPL = s.SymbolInformation.Property.IMPLICIT.value

  private def cls(sym: String, parents: List[s.Type], decls: List[String] = Nil) =
    s.SymbolInformation(
      symbol = sym,
      kind = s.SymbolInformation.Kind.CLASS,
      displayName = sym.stripPrefix(sym.takeWhile(_ != '/') + "/").stripSuffix("#"),
      signature = s.ClassSignature(None, parents, s.Type.Empty, Some(s.Scope(symlinks = decls)))
    )

  private def method(sym: String, sig: s.Signature, props: Int = 0) =
    s.SymbolInformation(
      symbol = sym,
      kind = s.SymbolInformation.Kind.METHOD,
      displayName = "m",
      signature = sig,
      properties = props
    )

  private def param(sym: String, tpe: s.Type, props: Int) =
    s.SymbolInformation(
      symbol = sym,
      kind = s.SymbolInformation.Kind.PARAMETER,
      displayName = "p",
      signature = s.ValueSignature(tpe),
      properties = props
    )

  private def methodSig(ret: s.Type, params: Seq[s.SymbolInformation] = Nil) =
    s.MethodSignature(None, if params.isEmpty then Nil else Seq(s.Scope(hardlinks = params)), ret)

  private def defn(sym: String, line: Int) =
    s.SymbolOccurrence(Some(s.Range(line, 0, line, 1)), sym, s.SymbolOccurrence.Role.DEFINITION)
  private def ref(sym: String, line: Int) =
    s.SymbolOccurrence(Some(s.Range(line, 0, line, 1)), sym, s.SymbolOccurrence.Role.REFERENCE)

  // core module: A (foundation), B (member references A), X<->Y (extends cycle)
  private val core = s.TextDocument(
    uri = "core/lib.scala",
    symbols = Vector(
      cls("core/A#", List(ObjectT), List("core/A#m().")),
      method("core/A#m().", methodSig(IntT)),
      cls("core/B#", List(ObjectT), List("core/B#f().")),
      method("core/B#f().", methodSig(tref("core/A#"))), // memberType: B -> A
      cls("core/X#", List(tref("core/Y#"))), // extends cycle
      cls("core/Y#", List(tref("core/X#")))
    ),
    occurrences = Vector(
      defn("core/A#", 0),
      defn("core/A#m().", 1),
      defn("core/B#", 2),
      defn("core/B#f().", 3),
      defn("core/X#", 4),
      defn("core/Y#", 5)
    )
  )

  // app module: C extends A, calls A#m, declares an implicit method depending on B
  private val implMk =
    method(
      "app/C#mk().",
      methodSig(s.Type.Empty, Seq(param("app/C#mk().(p)", tref("core/B#"), IMPL))),
      IMPL
    )
  private val app = s.TextDocument(
    uri = "app/main.scala",
    symbols = Vector(
      cls("app/C#", List(tref("core/A#")), List("app/C#go().", "app/C#mk().")),
      method("app/C#go().", methodSig(s.Type.Empty)),
      implMk
    ),
    occurrences = Vector(
      defn("app/C#", 0),
      defn("app/C#go().", 1),
      ref("core/A#m().", 2), // call: C#go -> A#m  ==> C -> A (cross-type, kept)
      ref("app/C#mk().", 2), // call: C#go -> C#mk ==> C -> C (same type, dropped by `from != to`)
      defn("app/C#mk().", 3)
    )
  )

  private val index = SemanticIndex(Vector(core, app))
  private val graphs = new DependencyGraphs(index)

  test("DependencyGraphs.nodes are the in-project classes only"):
    assertEquals(graphs.nodes, Set("core/A#", "core/B#", "core/X#", "core/Y#", "app/C#"))

  test("DependencyGraphs: one graph per dimension"):
    assertEquals(graphs.dimensions("extends")("app/C#"), Set("core/A#"))
    assertEquals(graphs.dimensions("extends")("core/X#"), Set("core/Y#"))
    assertEquals(graphs.dimensions("memberType")("core/B#"), Set("core/A#"))
    assertEquals(graphs.dimensions("call")("app/C#"), Set("core/A#"))
    assertEquals(
      graphs.dimensions("implicit"),
      Map("app/C#" -> Set("core/B#")),
      "only the given's dep"
    )
    assertEquals(graphs.dimensions("extends")("core/A#"), Set.empty[String], "no in-project parent")

  test("DependencyGraphs.combined unions the dimensions; moduleOf reads the uri segment"):
    assertEquals(graphs.combined("app/C#"), Set("core/A#", "core/B#"))
    assertEquals(graphs.combined("core/B#"), Set("core/A#"))
    assertEquals(graphs.moduleOf("core/A#"), "core")
    assertEquals(graphs.moduleOf("app/C#"), "app")
    assertEquals(graphs.moduleOf("nope/Z#"), "<unknown>", "no definition occurrence")

  test("StructureMetrics: per-type coupling, instability, and cycle membership"):
    val r = new StructureMetrics(index).result()
    val bySym = r.symbols.map(s => s.symbol -> s).toMap
    val a = bySym("core/A#").combined
    assertEquals(a.afferent, 2, "A is depended on by B and C")
    assertEquals(a.efferent, 0)
    assertEquals(a.instability, 0.0)
    assertEquals(a.layer, 0, "A is a foundation")
    assert(!a.inCycle)
    val c = bySym("app/C#").combined
    assertEquals(c.efferent, 2, "C depends on A and B")
    assertEquals(c.instability, 1.0)
    val x = bySym("core/X#").combined
    assertEquals(x.sccSize, 2, "X is in the X<->Y cycle")
    assert(x.inCycle)

  test("StructureMetrics: cycles and module rollup"):
    val r = new StructureMetrics(index).result()
    assert(
      r.cycles.exists(dc => dc.dimension == "extends" && dc.members == List("core/X#", "core/Y#")),
      r.cycles.toString
    )
    val byMod = r.modules.map(m => m.module -> m).toMap
    assertEquals(byMod("core").typeCount, 4, "A, B, X, Y")
    assertEquals(byMod("app").typeCount, 1)
    assertEquals(byMod("app").efferent, 1, "app depends on core")
    assertEquals(byMod("app").instability, 1.0)
    assertEquals(byMod("core").afferent, 1)
    assert(r.moduleEdges.exists(e => e.from == "app" && e.to == "core" && e.weight == 2))
