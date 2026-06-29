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
    // acyclic project: no module is in a cycle, and no module edge is flagged cyclic.
    assert(!byMod("app").inCycle, "app is acyclic")
    assert(!byMod("core").inCycle, "core is acyclic")
    assert(
      !r.moduleEdges.find(e => e.from == "app" && e.to == "core").get.inCycle,
      "an edge between acyclic modules is not cyclic"
    )
    // the per-type X<->Y cycle is reported in the combined overlay too, not only `extends`.
    assert(
      r.cycles.exists(dc => dc.dimension == "combined" && dc.members == List("core/X#", "core/Y#")),
      r.cycles.toString
    )
    // exactly the one real cycle per dimension — acyclic singletons are NOT reported as cycles.
    assertEquals(r.cycles.count(_.dimension == "extends"), 1, r.cycles.toString)

  // ============================ GraphMetrics: cycles & layering ================

  test("stronglyConnectedComponents detects a 3-node cycle as one component"):
    val g = Map("a" -> Set("b"), "b" -> Set("c"), "c" -> Set("a"), "d" -> Set("a"))
    val comps =
      GraphMetrics.stronglyConnectedComponents(Set("a", "b", "c", "d"), g).map(_.toList.sorted)
    assertEquals(comps.length, 2, comps.toString)
    assert(comps.contains(List("a", "b", "c")), comps.toString)
    assert(comps.contains(List("d")), comps.toString)

  test("layers: cross-component edges set the level around a condensed cycle"):
    // cycle x<->y; the cycle depends on foundation a; p depends on the cycle.
    val g = Map("x" -> Set("y", "a"), "y" -> Set("x"), "p" -> Set("x"))
    val l = GraphMetrics.layers(Set("a", "x", "y", "p"), g)
    assertEquals(l("a"), 0, "foundation")
    assertEquals(l("x"), 1, "cycle sits one level above the foundation")
    assertEquals(l("y"), 1, "cycle members share a level")
    assertEquals(l("p"), 2, "p depends on the cycle, so one level above it")

  // ============================ StructureMetrics: module cycles ================

  // Two modules in a cycle (m1/P extends m2/Q, m2/Q extends m1/P), plus m1/P also extends an
  // acyclic foundation m3/R. Exercises module-level cycle flags and the cyclic-edge classifier.
  private val cycP = s.TextDocument(
    uri = "m1/p.scala",
    symbols = Vector(cls("m1/P#", List(tref("m2/Q#"), tref("m3/R#")))),
    occurrences = Vector(defn("m1/P#", 0))
  )
  private val cycQ = s.TextDocument(
    uri = "m2/q.scala",
    symbols = Vector(cls("m2/Q#", List(tref("m1/P#")))),
    occurrences = Vector(defn("m2/Q#", 0))
  )
  private val cycR = s.TextDocument(
    uri = "m3/r.scala",
    symbols = Vector(cls("m3/R#", List(ObjectT))),
    occurrences = Vector(defn("m3/R#", 0))
  )
  private val cycIndex = SemanticIndex(Vector(cycP, cycQ, cycR))

  test("StructureMetrics: a module cycle flags its modules and edges, acyclic ones stay clean"):
    val r = new StructureMetrics(cycIndex).result()
    val byMod = r.modules.map(m => m.module -> m).toMap
    assertEquals(byMod("m1").sccSize, 2, "m1 and m2 form a module cycle")
    assert(byMod("m1").inCycle, "m1 is in the module cycle")
    assert(byMod("m2").inCycle, "m2 is in the module cycle")
    assert(!byMod("m3").inCycle, "m3 (foundation) is not in a cycle")
    def edge(from: String, to: String) =
      r.moduleEdges.find(e => e.from == from && e.to == to).getOrElse(fail(s"no edge $from->$to"))
    assert(edge("m1", "m2").inCycle, "edge inside the module cycle is cyclic")
    assert(edge("m2", "m1").inCycle, "edge inside the module cycle is cyclic")
    assert(
      !edge("m1", "m3").inCycle,
      "an edge from a cyclic module to an acyclic one is not itself cyclic"
    )

  // ============================ DependencyGraphs: edge-filter edge cases =======

  test("callGraph: a reference to a non-method member does not create a call edge"):
    // T#m() references U#x. (a non-method member of node U). The caller-state machine must keep
    // edges only to methods, so no T->U call edge is produced.
    val doc = s.TextDocument(
      uri = "cm/lib.scala",
      symbols = Vector(
        cls("cm/T#", List(ObjectT), List("cm/T#m().")),
        method("cm/T#m().", methodSig(s.Type.Empty)),
        cls("cm/U#", List(ObjectT))
      ),
      occurrences = Vector(
        defn("cm/T#", 0),
        defn("cm/T#m().", 1),
        ref("cm/U#x.", 2), // non-method member reference inside m
        defn("cm/U#", 5)
      )
    )
    val g = new DependencyGraphs(SemanticIndex(Vector(doc)))
    assertEquals(g.dimensions("call").getOrElse("cm/T#", Set.empty), Set.empty[String])

  test("implicitGraph: a non-implicit method with an implicit param creates no implicit edge"):
    // O#foo is NOT implicit, though it takes an implicit B. Only givens/implicits seed edges.
    val doc = s.TextDocument(
      uri = "i9/lib.scala",
      symbols = Vector(
        cls("i9/O#", List(ObjectT), List("i9/O#foo().")),
        method(
          "i9/O#foo().",
          methodSig(s.Type.Empty, Seq(param("i9/O#foo().(p)", tref("i9/B#"), IMPL))),
          props = 0
        ),
        cls("i9/B#", List(ObjectT))
      ),
      occurrences = Vector(defn("i9/O#", 0), defn("i9/O#foo().", 1), defn("i9/B#", 2))
    )
    val g = new DependencyGraphs(SemanticIndex(Vector(doc)))
    assertEquals(g.dimensions("implicit"), Map.empty[String, Set[String]])

  test("implicitGraph: an implicit whose owner is not a project node is dropped"):
    // g is implicit but lives under p/o. (no class node), so it must not appear as an edge source.
    val doc = s.TextDocument(
      uri = "i6/lib.scala",
      symbols = Vector(
        cls("i6/B#", List(ObjectT)),
        method(
          "i6/p.g().",
          methodSig(s.Type.Empty, Seq(param("i6/p.g().(x)", tref("i6/B#"), IMPL))),
          props = IMPL
        )
      ),
      occurrences = Vector(defn("i6/B#", 0), defn("i6/p.g().", 1))
    )
    val g = new DependencyGraphs(SemanticIndex(Vector(doc)))
    assertEquals(g.dimensions("implicit"), Map.empty[String, Set[String]])

  test("implicitGraph: a given depending on its own type creates no self-edge"):
    // O#mk is an implicit taking an implicit O — the self-dependency must be filtered out.
    val doc = s.TextDocument(
      uri = "i8/lib.scala",
      symbols = Vector(
        cls("i8/O#", List(ObjectT), List("i8/O#mk().")),
        method(
          "i8/O#mk().",
          methodSig(s.Type.Empty, Seq(param("i8/O#mk().(s)", tref("i8/O#"), IMPL))),
          props = IMPL
        )
      ),
      occurrences = Vector(defn("i8/O#", 0), defn("i8/O#mk().", 1))
    )
    val g = new DependencyGraphs(SemanticIndex(Vector(doc)))
    assert(!g.dimensions("implicit").getOrElse("i8/O#", Set.empty).contains("i8/O#"))
