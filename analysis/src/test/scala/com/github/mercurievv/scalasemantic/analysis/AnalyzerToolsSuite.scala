package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Deterministic unit tests for the remaining `Analyzer` tools (outline, find-symbol, move-plan,
  * extract-method-plan, ranked structure, type-at-position, trace-implicit-chain), driven against
  * hand-built in-memory indexes. Filter-safe (no live dogfooding), so they belong in stryker4s.conf
  * and cover the tool methods that only AnalyzerSuite/AnalyzerPcSuite reached.
  */
class AnalyzerToolsSuite extends munit.FunSuite:

  private def tref(sym: String): s.Type = s.TypeRef(s.Type.Empty, sym, Nil)
  private val IntT = tref("scala/Int#")
  private val IMPL = s.SymbolInformation.Property.IMPLICIT.value

  private def si(
      sym: String,
      kind: s.SymbolInformation.Kind,
      display: String,
      signature: s.Signature = s.NoSignature,
      props: Int = 0
  ) =
    s.SymbolInformation(
      symbol = sym,
      kind = kind,
      displayName = display,
      signature = signature,
      properties = props
    )

  private def occ(sym: String, role: s.SymbolOccurrence.Role, l1: Int, c1: Int, l2: Int, c2: Int) =
    s.SymbolOccurrence(Some(s.Range(l1, c1, l2, c2)), sym, role)
  private val DEF = s.SymbolOccurrence.Role.DEFINITION
  private val REF = s.SymbolOccurrence.Role.REFERENCE

  private def index(docs: s.TextDocument*) = SemanticIndex(docs.toVector)
  private def doc(uri: String, symbols: Seq[s.SymbolInformation], occs: Seq[s.SymbolOccurrence]) =
    s.TextDocument(uri = uri, symbols = symbols.toVector, occurrences = occs.toVector)

  private def docUri(v: String) = DocumentUri.from(v).fold(fail(_), identity)

  // ============================== outline =====================================

  test("outline nests members under their type with per-entry signatures"):
    val symbols = Seq(
      si("o/C#", s.SymbolInformation.Kind.CLASS, "C"),
      si("o/C#m().", s.SymbolInformation.Kind.METHOD, "m", s.MethodSignature(None, Nil, IntT)),
      si("o/C#v.", s.SymbolInformation.Kind.FIELD, "v", s.ValueSignature(IntT)),
      si(
        "o/C#`<init>`().",
        s.SymbolInformation.Kind.METHOD,
        "<init>",
        s.MethodSignature(None, Nil, IntT)
      )
    )
    val occs = Seq(
      occ("o/C#", DEF, 0, 0, 0, 1),
      occ("o/C#m().", DEF, 1, 2, 1, 3),
      occ("o/C#v.", DEF, 2, 2, 2, 3),
      occ("o/C#`<init>`().", DEF, 0, 0, 0, 1) // excluded by name
    )
    val az = Analyzer(index(doc("o.scala", symbols, occs)))
    val top = az.outline(docUri("o.scala")).getOrElse(fail("not indexed"))
    assertEquals(top.map(_.symbol), List("o/C#"), "one top-level type, <init> excluded")
    val kids = top.head.children
    assertEquals(kids.map(_.name), List("m", "v"), "members nested and ordered by line")
    assert(kids.find(_.name == "m").get.signature.startsWith("def m"), "method signature")
    assertEquals(kids.find(_.name == "v").get.signature, ": Int", "value type")
    assertEquals(top.head.signature, "", "a type needs no signature line")

  // ============================ find-symbol ===================================

  private val findIdx = index(
    doc(
      "f.scala",
      Seq(
        si("f/Animal#", s.SymbolInformation.Kind.CLASS, "Animal"),
        si("f/AnimalKind#", s.SymbolInformation.Kind.CLASS, "AnimalKind"),
        si("f/Zoo#animal().", s.SymbolInformation.Kind.METHOD, "animal"),
        si("f/Zoo#`<init>`().", s.SymbolInformation.Kind.METHOD, "<init>"),
        si("f/Zoo#animal().(p)", s.SymbolInformation.Kind.PARAMETER, "animal") // excluded by kind
      ),
      Nil
    )
  )

  test("findSymbol: exact vs substring, excludes <init>/params, ranks exact>prefix>substring"):
    val az = Analyzer(findIdx)
    assertEquals(
      az.findSymbol("animal", exact = true).map(_.displayName),
      List("Animal", "animal"),
      "exact (case-insensitive); AnimalKind/<init>/param excluded"
    )
    assertEquals(
      az.findSymbol("anim").map(_.displayName),
      List("Animal", "animal", "AnimalKind"),
      "substring, ranked exact/prefix then by length"
    )
    assertEquals(
      az.findSymbol("animal", kind = Some("CLASS")).map(_.displayName).toSet,
      Set("Animal", "AnimalKind"),
      "kind filter keeps only classes"
    )
    assertEquals(az.findSymbol("animal", limit = lim(1)).size, 1, "limit applies")
    assert(az.findSymbol("nomatch").isEmpty)

  private def lim(n: Int) = PositiveInt.from(n, "limit").fold(fail(_), identity)

  // ============================== move-plan ===================================

  test("movePlan: per-file import edits decided by each referrer's own package"):
    val foo = doc(
      "pkg/a.scala",
      Seq(si("m/pkg/Foo#", s.SymbolInformation.Kind.CLASS, "Foo")),
      Seq(occ("m/pkg/Foo#", DEF, 0, 0, 0, 3))
    )
    // a referrer already in the destination package -> no import
    val inDest = doc(
      "dest/b.scala",
      Seq(si("m/dest/B#", s.SymbolInformation.Kind.CLASS, "B")),
      Seq(occ("m/dest/B#", DEF, 0, 0, 0, 1), occ("m/pkg/Foo#", REF, 1, 4, 1, 7))
    )
    // a referrer in the source package -> add the new FQN (nothing to remove)
    val inSource = doc(
      "pkg/c.scala",
      Seq(si("m/pkg/C#", s.SymbolInformation.Kind.CLASS, "C")),
      Seq(occ("m/pkg/C#", DEF, 0, 0, 0, 1), occ("m/pkg/Foo#", REF, 1, 4, 1, 7))
    )
    // a referrer elsewhere -> swap old FQN for new
    val elsewhere = doc(
      "other/d.scala",
      Seq(si("m/other/D#", s.SymbolInformation.Kind.CLASS, "D")),
      Seq(occ("m/other/D#", DEF, 0, 0, 0, 1), occ("m/pkg/Foo#", REF, 1, 4, 1, 7))
    )
    val az = Analyzer(index(foo, inDest, inSource, elsewhere))
    val sym = SemanticDbSymbol.from("m/pkg/Foo#").fold(fail(_), identity)
    val dest = PackageSymbol.from("m/dest/").fold(fail(_), identity)
    val plan = az.movePlan(sym, dest)
    assertEquals(plan.fromFqn, "m.pkg.Foo")
    assertEquals(plan.toFqn, "m.dest.Foo")
    assertEquals(plan.references.size, 3, "every cross-file use")
    assertEquals(
      plan.imports.toSet,
      Set(
        MoveImport("pkg/c.scala", "", "m.dest.Foo"),
        MoveImport("other/d.scala", "m.pkg.Foo", "m.dest.Foo")
      ),
      "dest-package referrer needs no import"
    )

  // ========================== extract-method-plan =============================

  test("extractMethodPlan: free-var params, escaping returns, rendered signature/call"):
    def local(n: String, display: String) =
      si(n, s.SymbolInformation.Kind.LOCAL, display, s.ValueSignature(IntT))
    val symbols = Seq(
      si("ex/M#run().", s.SymbolInformation.Kind.METHOD, "run", s.MethodSignature(None, Nil, IntT)),
      local("local0", "a"), // defined before, read inside  -> param
      local("local1", "b"), // defined inside, read after   -> return
      local("local2", "c") // defined and read inside only  -> neither
    )
    val occs = Seq(
      occ("ex/M#run().", DEF, 0, 2, 0, 5),
      occ("local0", DEF, 1, 8, 1, 9), // outside selection (before)
      occ("local0", REF, 3, 10, 3, 11), // inside  -> a is a free read
      occ("local1", DEF, 3, 4, 3, 5), // inside
      occ("local2", DEF, 4, 4, 4, 5), // inside
      occ("local2", REF, 4, 8, 4, 9), // inside (not escaping)
      occ("local1", REF, 6, 4, 6, 5) // after selection -> b escapes
    )
    val az = Analyzer(index(doc("ex.scala", symbols, occs)))
    val range = SourceRange.from(2, 0, 5, 0).fold(fail(_), identity)
    val name = ScalaIdentifier.from("extracted").fold(fail(_), identity)
    val plan = az.extractMethodPlan(docUri("ex.scala"), range, name).getOrElse(fail("not indexed"))
    assertEquals(plan.parameters.map(_.name), List("a"), "free var read inside")
    assertEquals(plan.returns.map(_.name), List("b"), "defined inside, read after")
    assertEquals(plan.returnType, "Int")
    assertEquals(plan.signature, "def extracted(a: Int): Int")
    assertEquals(plan.call, "val b = extracted(a)")
    assertEquals(plan.enclosingMethod.map(_.displayName), Some("run"))

  // ======================== ranked structure symbols ==========================

  test("rankedStructureSymbols sorts by the chosen metric and honours the limit"):
    // B -> A (B extends A): A has afferent 1, B has efferent 1.
    val symbols = Seq(
      si(
        "s/A#",
        s.SymbolInformation.Kind.CLASS,
        "A",
        s.ClassSignature(None, List(tref("java/lang/Object#")), s.Type.Empty, Some(s.Scope()))
      ),
      si(
        "s/B#",
        s.SymbolInformation.Kind.CLASS,
        "B",
        s.ClassSignature(None, List(tref("s/A#")), s.Type.Empty, Some(s.Scope()))
      )
    )
    val occs = Seq(occ("s/A#", DEF, 0, 0, 0, 1), occ("s/B#", DEF, 1, 0, 1, 1))
    val az = Analyzer(index(doc("s/lib.scala", symbols, occs)))
    val byAfferent = az
      .rankedStructureSymbols(StructureDimension.Combined, StructureSort.Afferent, lim(1))
      .map((sym, _) => sym.displayName)
    assertEquals(byAfferent, List("A"), "A (depended on) ranks highest by afferent, limit 1")
    val byEfferent = az
      .rankedStructureSymbols(StructureDimension.Combined, StructureSort.Efferent, lim(1))
      .map((sym, _) => sym.displayName)
    assertEquals(byEfferent, List("B"), "B (depends on A) ranks highest by efferent")

  // =========================== type-at-position ===============================

  test("typeAtPosition picks the smallest occurrence covering the point"):
    val symbols = Seq(
      si("t/T#", s.SymbolInformation.Kind.CLASS, "T"),
      si("t/T#m().", s.SymbolInformation.Kind.METHOD, "m", s.MethodSignature(None, Nil, IntT))
    )
    val occs = Seq(
      occ("t/T#", REF, 1, 0, 1, 9), // wide span
      occ("t/T#m().", REF, 1, 2, 1, 3) // narrow span over the same point
    )
    val az = Analyzer(index(doc("t.scala", symbols, occs)))
    val pos = SourcePosition.from(1, 2).fold(fail(_), identity)
    val at = az.typeAtPosition(docUri("t.scala"), pos).getOrElse(fail("nothing at position"))
    assertEquals(at.symbol, "t/T#m().", "the tighter range wins")
    assertEquals(at.displayName, "m")

  // ========================= trace-implicit-chain =============================

  test("traceImplicitChain walks given dependencies transitively"):
    def givenObj(sym: String, display: String, produces: String) =
      si(
        sym,
        s.SymbolInformation.Kind.OBJECT,
        display,
        s.ClassSignature(None, List(tref(produces), tref("java/lang/Object#")), s.Type.Empty, None),
        IMPL
      )
    def givenDef(sym: String, display: String, produces: String, dep: String) =
      si(
        sym,
        s.SymbolInformation.Kind.METHOD,
        display,
        s.MethodSignature(
          None,
          Seq(
            s.Scope(hardlinks =
              Vector(
                si(
                  sym + "(p)",
                  s.SymbolInformation.Kind.PARAMETER,
                  "p",
                  s.ValueSignature(tref(dep)),
                  IMPL
                )
              )
            )
          ),
          tref(produces)
        ),
        IMPL
      )
    val symbols = Seq(
      si("ic/Show#", s.SymbolInformation.Kind.CLASS, "Show"),
      si("ic/Eq#", s.SymbolInformation.Kind.CLASS, "Eq"),
      givenDef("ic/showFromEq().", "showFromEq", "ic/Show#", "ic/Eq#"), // Show needs Eq
      givenObj("ic/eqInst.", "eqInst", "ic/Eq#") // Eq provided
    )
    val az = Analyzer(index(doc("ic.scala", symbols, Nil)))
    val sym = TypeSymbol.from("ic/Show#").fold(fail(_), identity)
    val chain = az.traceImplicitChain(sym)
    assertEquals(chain.queryType, "ic/Show#")
    assert(chain.steps.exists(_.target.displayName == "showFromEq"), chain.steps.toString)
    assert(chain.steps.exists(_.target.displayName == "eqInst"), "transitive Eq given reached")
