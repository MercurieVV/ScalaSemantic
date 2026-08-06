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

  // ======================== outline filtering (#285) ==========================

  // Outer -> Inner -> deep, plus a sibling method, so ancestor context and depth bounds are
  // distinguishable from a flat name match.
  private val nestedIdx =
    val symbols = Seq(
      si("n/Outer#", s.SymbolInformation.Kind.CLASS, "Outer"),
      si("n/Outer#Inner#", s.SymbolInformation.Kind.CLASS, "Inner"),
      si("n/Outer#Inner#deep().", s.SymbolInformation.Kind.METHOD, "deep"),
      si("n/Outer#other().", s.SymbolInformation.Kind.METHOD, "other")
    )
    val occs = Seq(
      occ("n/Outer#", DEF, 0, 0, 0, 5),
      occ("n/Outer#Inner#", DEF, 1, 2, 1, 7),
      occ("n/Outer#Inner#deep().", DEF, 2, 4, 2, 8),
      occ("n/Outer#other().", DEF, 3, 2, 3, 7)
    )
    index(doc("n.scala", symbols, occs))

  private val nestedAz = Analyzer(nestedIdx)
  private val nestedUri = docUri("n.scala")

  test("outlineFiltered with no filters is the full outline"):
    assertEquals(nestedAz.outlineFiltered(nestedUri), nestedAz.outline(nestedUri))

  test("outlineFiltered query keeps the match and its enclosing scopes as context"):
    val top = nestedAz
      .outlineFiltered(nestedUri, query = Some("inner"))
      .getOrElse(fail("not indexed"))
    assertEquals(top.map(_.name), List("Outer"), "the enclosing type is kept as context")
    assertEquals(top.head.children.map(_.name), List("Inner"), "the sibling method is dropped")
    assertEquals(top.head.children.head.children.map(_.name), List("deep"), "match keeps subtree")

  test("outlineFiltered includeParents=false returns matches as roots"):
    val top = nestedAz
      .outlineFiltered(nestedUri, query = Some("inner"), includeParents = false)
      .getOrElse(fail("not indexed"))
    assertEquals(top.map(_.name), List("Inner"))

  test("outlineFiltered symbol matches one exact declaration"):
    val top = nestedAz
      .outlineFiltered(nestedUri, symbol = Some("n/Outer#other()."), includeParents = false)
      .getOrElse(fail("not indexed"))
    assertEquals(top.map(_.symbol), List("n/Outer#other()."))

  test("outlineFiltered maxDepth bounds the subtree kept below a match, not the file"):
    val depth1 = nestedAz
      .outlineFiltered(nestedUri, query = Some("inner"), includeParents = false, maxDepth = Some(1))
      .getOrElse(fail("not indexed"))
    assertEquals(depth1.map(_.name), List("Inner"))
    assertEquals(depth1.head.children, Nil, "maxDepth=1 keeps the match alone")
    // …and on its own it is a plain depth limit over the whole file.
    val roots = nestedAz
      .outlineFiltered(nestedUri, maxDepth = Some(1))
      .getOrElse(fail("not indexed"))
    assertEquals(roots.map(_.name), List("Outer"))
    assertEquals(roots.head.children, Nil)

  test("outlineFiltered kind keeps only declarations of that kind"):
    val methods = nestedAz
      .outlineFiltered(nestedUri, kind = Some("method"), includeParents = false)
      .getOrElse(fail("not indexed"))
    assertEquals(methods.map(_.name).sorted, List("deep", "other"), "case-insensitive kind match")

  test("outlineFiltered returns an empty outline when nothing matches, not the whole file"):
    assertEquals(nestedAz.outlineFiltered(nestedUri, query = Some("nosuchname")), Some(Nil))

  test("outlineFiltered of an unindexed uri stays None"):
    assertEquals(nestedAz.outlineFiltered(docUri("missing.scala"), query = Some("x")), None)

  // ====================== product records (#286) ==============================

  // A case class with everything the compiler generates, plus decoys: `helper` is a method that is
  // NOT a constructor parameter (so not an accessor), `y` is a parameter whose accessor is never
  // used (so its group must be omitted), and `Plain` is a non-case class.
  private val CASE = s.SymbolInformation.Property.CASE.value

  private val productIdx =
    def m(sym: String, display: String) = si(sym, s.SymbolInformation.Kind.METHOD, display)
    def scope(syms: String*) = Some(s.Scope(symlinks = syms.toVector))
    val symbols = Seq(
      si(
        "p/Foo#",
        s.SymbolInformation.Kind.CLASS,
        "Foo",
        s.ClassSignature(
          None,
          Nil,
          s.Type.Empty,
          scope("p/Foo#`<init>`().", "p/Foo#copy().", "p/Foo#x.", "p/Foo#y.", "p/Foo#helper().")
        ),
        props = CASE
      ),
      si(
        "p/Foo.",
        s.SymbolInformation.Kind.OBJECT,
        "Foo",
        s.ClassSignature(None, Nil, s.Type.Empty, scope("p/Foo.apply().", "p/Foo.unapply()."))
      ),
      si(
        "p/Foo#`<init>`().",
        s.SymbolInformation.Kind.METHOD,
        "<init>",
        s.MethodSignature(None, Seq(s.Scope(symlinks = Vector("p/Foo#x.", "p/Foo#y."))), IntT)
      ),
      si("p/Foo#x.", s.SymbolInformation.Kind.PARAMETER, "x"),
      si("p/Foo#y.", s.SymbolInformation.Kind.PARAMETER, "y"),
      m("p/Foo#copy().", "copy"),
      m("p/Foo#helper().", "helper"),
      m("p/Foo.apply().", "apply"),
      m("p/Foo.unapply().", "unapply"),
      si("p/Plain#", s.SymbolInformation.Kind.CLASS, "Plain")
    )
    // `p/Foo#y.` deliberately has no occurrence; `p/Ghost#` deliberately has no SymbolInformation.
    val occs = Seq(
      occ("p/Foo#", DEF, 0, 0, 0, 3),
      occ("p/Foo#", REF, 1, 0, 1, 3),
      occ("p/Foo.", REF, 2, 0, 2, 3),
      occ("p/Foo#`<init>`().", REF, 3, 0, 3, 3),
      occ("p/Foo#copy().", REF, 4, 0, 4, 4),
      occ("p/Foo#x.", REF, 5, 0, 5, 1),
      occ("p/Foo#helper().", REF, 6, 0, 6, 6),
      occ("p/Foo.apply().", REF, 7, 0, 7, 5),
      occ("p/Foo.unapply().", REF, 8, 0, 8, 7),
      occ("p/Ghost#", REF, 9, 0, 9, 5)
    )
    index(doc("p.scala", symbols, occs))

  private val productHelpers = new AnalyzerHelpers(productIdx)

  test("relatedProductSymbols labels exactly the generated members, and nothing else"):
    assertEquals(
      productHelpers.relatedProductSymbols("p/Foo#"),
      List(
        "companion" -> "p/Foo.",
        "constructors" -> "p/Foo#`<init>`().",
        "copy" -> "p/Foo#copy().",
        "accessors" -> "p/Foo#x.",
        "accessors" -> "p/Foo#y.",
        "apply" -> "p/Foo.apply().",
        "unapply" -> "p/Foo.unapply()."
      ),
      "`helper` is a method but not a constructor parameter, so it is not an accessor"
    )

  test("relatedProductSymbols works from the companion spelling and drops the queried symbol"):
    assertEquals(
      productHelpers.relatedProductSymbols("p/Foo."),
      List(
        "constructors" -> "p/Foo#`<init>`().",
        "copy" -> "p/Foo#copy().",
        "accessors" -> "p/Foo#x.",
        "accessors" -> "p/Foo#y.",
        "apply" -> "p/Foo.apply().",
        "unapply" -> "p/Foo.unapply()."
      ),
      "querying the companion must not relate it to itself"
    )

  test("relatedProductSymbols is empty for a non-case class and for an unknown symbol"):
    assertEquals(productHelpers.relatedProductSymbols("p/Plain#"), Nil)
    assertEquals(productHelpers.relatedProductSymbols("p/Nope#"), Nil)

  test("isCaseLike is false for a plain class and for a symbol the index does not know"):
    assert(productHelpers.isCaseLike("p/Foo#"))
    assert(!productHelpers.isCaseLike("p/Plain#"), "no CASE property")
    assert(!productHelpers.isCaseLike("p/Nope#"), "an unknown symbol is not case-like")
    // Known only by an occurrence, with no SymbolInformation: absent properties must read as "not
    // a case class", never as "no evidence against it".
    assert(!productHelpers.isCaseLike("p/Ghost#"), "no SymbolInformation ⇒ not case-like")

  test("classSymbolOf normalises the companion spelling and rejects what the index lacks"):
    assertEquals(productHelpers.classSymbolOf("p/Foo#"), Some("p/Foo#"))
    assertEquals(productHelpers.classSymbolOf("p/Foo."), Some("p/Foo#"))
    assertEquals(productHelpers.classSymbolOf("p/Nope#"), None, "unknown symbols are not invented")
    assertEquals(productHelpers.classSymbolOf("p/Foo#m()."), None, "not a type or term spelling")
    // occurrence-only symbols count as known: a referenced type need not carry SymbolInformation.
    assertEquals(productHelpers.classSymbolOf("p/Ghost#"), Some("p/Ghost#"))

  test("companionObjectOf only derives from a class symbol"):
    assertEquals(productHelpers.companionObjectOf("p/Foo#"), Some("p/Foo."))
    assertEquals(productHelpers.companionObjectOf("p/Foo."), None, "already the companion")
    assertEquals(productHelpers.companionObjectOf("p/Plain#"), None, "no companion in the index")

  test("findUsages omits a related group whose symbol has no occurrences"):
    val az = Analyzer(productIdx)
    val sym = SemanticDbSymbol.from("p/Foo#").fold(fail(_), identity)
    val groups = az.findUsages(sym).related
    assertEquals(
      groups.map(g => g.kind -> g.symbol),
      List(
        "companion" -> "p/Foo.",
        "constructors" -> "p/Foo#`<init>`().",
        "copy" -> "p/Foo#copy().",
        "accessors" -> "p/Foo#x.",
        "apply" -> "p/Foo.apply().",
        "unapply" -> "p/Foo.unapply()."
      ),
      "`y` is a parameter accessor with no uses, so it contributes no group"
    )
    assert(groups.forall(_.locations.nonEmpty))

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
    // the definition is the DEFINITION occurrence, not the first reference (kills `== DEFINITION`->`!=`)
    assertEquals(plan.definition.map(_.uri), Some("pkg/a.scala"), "the def site, not a referrer")
    assertEquals(plan.definition.map(_.range.start.character), Some(0))
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

  // ===================== enclosing-definition-range ============================

  private val enclosingIdx = index(
    doc(
      "o.scala",
      Seq(
        si("o/O#", s.SymbolInformation.Kind.OBJECT, "O"),
        si("o/O#m1().", s.SymbolInformation.Kind.METHOD, "m1", s.MethodSignature(None, Nil, IntT)),
        si("o/O#m2().", s.SymbolInformation.Kind.METHOD, "m2", s.MethodSignature(None, Nil, IntT))
      ),
      Seq(
        occ("o/O#", DEF, 0, 0, 0, 1),
        occ("o/O#m1().", DEF, 1, 2, 1, 4), // def m1 at line 1
        occ("o/O#m2().", REF, 2, 4, 2, 6), // m1's body calls m2 at line 2
        occ("o/O#m2().", DEF, 3, 2, 3, 4) // def m2 at line 3
      )
    )
  )

  test(
    "enclosingDefinitionRange anchors to the enclosing def, not the callee referenced at the position"
  ):
    val az = Analyzer(enclosingIdx)
    // position sits on the `m2` REFERENCE inside m1's body, not on m2's own definition
    val pos = SourcePosition.from(2, 5).fold(fail(_), identity)
    val (sym, range) =
      az.enclosingDefinitionRange(docUri("o.scala"), pos).getOrElse(fail("no enclosing def"))
    assertEquals(sym, "o/O#m1().", "anchors to the enclosing m1, not the referenced m2")
    assertEquals(range.startLine, 1)
    assertEquals(range.endLine, 3, "span runs up to the next sibling (m2)")

  test("enclosingDefinitionRange resolves directly when the position IS a definition"):
    val az = Analyzer(enclosingIdx)
    val pos = SourcePosition.from(3, 2).fold(fail(_), identity)
    val (sym, _) =
      az.enclosingDefinitionRange(docUri("o.scala"), pos).getOrElse(fail("no enclosing def"))
    assertEquals(sym, "o/O#m2().")

  test("enclosingDefinitionRange returns None before any declaration (no enclosing def)"):
    val az = Analyzer(enclosingIdx)
    val pos = SourcePosition.from(0, 0).fold(fail(_), identity)
    // line 0 is O's own opening line, which DOES enclose position 0 — use a document with no
    // occurrences at all to hit the genuine "nothing encloses this" case.
    val empty = Analyzer(index(doc("empty.scala", Nil, Nil)))
    assertEquals(empty.enclosingDefinitionRange(docUri("empty.scala"), pos), None)
    assert(az.enclosingDefinitionRange(docUri("o.scala"), pos).isDefined, "line 0 is inside O")

  // ===================== find-symbol: ranking & exclusions ====================

  test("findSymbol ranks exact > prefix > substring, excludes empty-named and <init>"):
    val az = Analyzer(
      index(
        doc(
          "r.scala",
          Seq(
            si("r/cat#", s.SymbolInformation.Kind.CLASS, "cat"), // exact
            si("r/category#", s.SymbolInformation.Kind.CLASS, "category"), // prefix
            si("r/scat#", s.SymbolInformation.Kind.CLASS, "scat"), // substring
            si("r/empty#", s.SymbolInformation.Kind.CLASS, ""), // empty display -> excluded
            si("r/Z#`<init>`().", s.SymbolInformation.Kind.METHOD, "<init>") // excluded by name
          ),
          Nil
        )
      )
    )
    assertEquals(
      az.findSymbol("cat").map(_.displayName),
      List("cat", "category", "scat"),
      "exact first, then prefix, then substring — not by length alone"
    )
    assert(az.findSymbol("init").isEmpty, "<init> excluded by name even when it matches")
    assert(!az.findSymbol("").map(_.displayName).contains(""), "empty display name excluded")

  // ============== extract-method-plan: return arities & range-less ============

  private def exLocal(n: String, display: String) =
    si(n, s.SymbolInformation.Kind.LOCAL, display, s.ValueSignature(IntT))
  private val exMethod =
    si("ex/M#run().", s.SymbolInformation.Kind.METHOD, "run", s.MethodSignature(None, Nil, IntT))

  test("extractMethodPlan: no escaping local → Unit return and a bare call"):
    // local0 is defined and read only inside the selection: neither a param nor a return.
    val occs = Seq(
      occ("ex/M#run().", DEF, 0, 2, 0, 5),
      occ("local0", DEF, 3, 4, 3, 5), // inside
      occ("local0", REF, 3, 8, 3, 9) // inside
    )
    val az = Analyzer(index(doc("ex.scala", Seq(exMethod, exLocal("local0", "a")), occs)))
    val range = SourceRange.from(2, 0, 5, 0).fold(fail(_), identity)
    val name = ScalaIdentifier.from("ex0").fold(fail(_), identity)
    val plan = az.extractMethodPlan(docUri("ex.scala"), range, name).getOrElse(fail("not indexed"))
    assertEquals(plan.parameters, Nil)
    assertEquals(plan.returns, Nil)
    assertEquals(plan.returnType, "Unit")
    assertEquals(plan.signature, "def ex0(): Unit")
    assertEquals(plan.call, "ex0()")

  test("extractMethodPlan: several escaping locals → tuple return and destructuring call"):
    // a: free read (param). b, c: defined inside and read after (tuple return).
    val symbols =
      Seq(exMethod, exLocal("local0", "a"), exLocal("local1", "b"), exLocal("local2", "c"))
    val occs = Seq(
      occ("ex/M#run().", DEF, 0, 2, 0, 5),
      occ("local0", DEF, 1, 8, 1, 9), // before selection
      occ("local0", REF, 3, 10, 3, 11), // inside -> param a
      occ("local1", DEF, 3, 4, 3, 5), // inside
      occ("local2", DEF, 4, 4, 4, 5), // inside
      occ("local1", REF, 6, 4, 6, 5), // after -> b escapes
      occ("local2", REF, 7, 4, 7, 5) // after -> c escapes
    )
    val az = Analyzer(index(doc("ex.scala", symbols, occs)))
    val range = SourceRange.from(2, 0, 5, 0).fold(fail(_), identity)
    val name = ScalaIdentifier.from("ex2").fold(fail(_), identity)
    val plan = az.extractMethodPlan(docUri("ex.scala"), range, name).getOrElse(fail("not indexed"))
    assertEquals(plan.parameters.map(_.name), List("a"))
    assertEquals(plan.returns.map(_.name), List("b", "c"))
    assertEquals(plan.returnType, "(Int, Int)")
    assertEquals(plan.signature, "def ex2(a: Int): (Int, Int)")
    assertEquals(plan.call, "val (b, c) = ex2(a)")

  // The range-less-occurrence exclusion and the free-read/defined-outside-is-not-a-return rule are
  // now covered generatively in AnalyzerToolsPropertySuite ("extractMethodPlan: parameter iff
  // ranged-read-inside without ranged-def-inside; ..."), over an arbitrary number of locals and
  // occurrence-shape combinations. Kept here: the two-param rendering with an "and" separator,
  // which is a formatting detail the property doesn't assert.
  test("extractMethodPlan: two free-var params render with a ', ' separator"):
    val symbols = Seq(exMethod, exLocal("local0", "a"), exLocal("local1", "b"))
    val occs = Seq(
      occ("ex/M#run().", DEF, 0, 2, 0, 5),
      occ("local0", DEF, 1, 8, 1, 9), // before
      occ("local1", DEF, 1, 12, 1, 13), // before
      occ("local0", REF, 3, 4, 3, 5), // inside -> a
      occ("local1", REF, 3, 8, 3, 9) // inside -> b
    )
    val az = Analyzer(index(doc("ex.scala", symbols, occs)))
    val range = SourceRange.from(2, 0, 5, 0).fold(fail(_), identity)
    val name = ScalaIdentifier.from("exp").fold(fail(_), identity)
    val plan = az.extractMethodPlan(docUri("ex.scala"), range, name).getOrElse(fail("not indexed"))
    assertEquals(plan.signature, "def exp(a: Int, b: Int): Unit")
    assertEquals(plan.call, "exp(a, b)")

  test("extractMethodPlan: a range-less method definition is not chosen as the enclosing method"):
    val symbols = Seq(
      si(
        "ex/M#lone().",
        s.SymbolInformation.Kind.METHOD,
        "lone",
        s.MethodSignature(None, Nil, IntT)
      ),
      exLocal("local0", "a")
    )
    val occs = Seq(
      s.SymbolOccurrence(None, "ex/M#lone().", DEF), // range-less method def
      occ("local0", DEF, 3, 4, 3, 5),
      occ("local0", REF, 3, 8, 3, 9)
    )
    val az = Analyzer(index(doc("ex.scala", symbols, occs)))
    val range = SourceRange.from(2, 0, 5, 0).fold(fail(_), identity)
    val name = ScalaIdentifier.from("exe").fold(fail(_), identity)
    val plan = az.extractMethodPlan(docUri("ex.scala"), range, name).getOrElse(fail("not indexed"))
    assertEquals(plan.enclosingMethod, None, "a range-less method def cannot enclose the selection")

  // ==================== ranked structure symbols: path filter =================

  test("rankedStructureSymbols: a non-empty pathFilter keeps only matching modules"):
    // two modules: core/A and app/B(extends A). A filter of "core" must drop app/B.
    val symbols = Seq(
      si(
        "core/A#",
        s.SymbolInformation.Kind.CLASS,
        "A",
        s.ClassSignature(None, List(tref("java/lang/Object#")), s.Type.Empty, Some(s.Scope()))
      ),
      si(
        "app/B#",
        s.SymbolInformation.Kind.CLASS,
        "B",
        s.ClassSignature(None, List(tref("core/A#")), s.Type.Empty, Some(s.Scope()))
      )
    )
    val az = Analyzer(
      index(
        doc("core/a.scala", Seq(symbols(0)), Seq(occ("core/A#", DEF, 0, 0, 0, 1))),
        doc("app/b.scala", Seq(symbols(1)), Seq(occ("app/B#", DEF, 0, 0, 0, 1)))
      )
    )
    val all = az
      .rankedStructureSymbols(StructureDimension.Combined, StructureSort.Afferent, lim(10))
      .map((sym, _) => sym.module)
      .toSet
    assertEquals(all, Set("core", "app"), "no filter keeps both modules")
    val coreOnly = az
      .rankedStructureSymbols(
        StructureDimension.Combined,
        StructureSort.Afferent,
        lim(10),
        Some("core")
      )
      .map((sym, _) => sym.module)
      .toSet
    assertEquals(coreOnly, Set("core"), "pathFilter 'core' drops the app module")

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

  test("traceImplicitChain resolves concrete applied types into a nested tree"):
    def tapp(sym: String, args: s.Type*) = s.TypeRef(s.Type.Empty, sym, args)
    val aParam = si("tc/Show.listShow().[A]", s.SymbolInformation.Kind.TYPE_PARAMETER, "A")
    def givenObj(sym: String, display: String, produces: s.Type) =
      si(
        sym,
        s.SymbolInformation.Kind.OBJECT,
        display,
        s.ClassSignature(None, List(produces, tref("java/lang/Object#")), s.Type.Empty, None),
        IMPL
      )
    val listShow =
      si(
        "tc/Show.listShow().",
        s.SymbolInformation.Kind.METHOD,
        "listShow",
        s.MethodSignature(
          Some(s.Scope(hardlinks = Vector(aParam))),
          Seq(
            s.Scope(hardlinks =
              Vector(
                si(
                  "tc/Show.listShow().(p)",
                  s.SymbolInformation.Kind.PARAMETER,
                  "p",
                  s.ValueSignature(tapp("tc/Show#", tref(aParam.symbol))),
                  IMPL
                )
              )
            )
          ),
          tapp("tc/Show#", tapp("scala/package.List#", tref(aParam.symbol)))
        ),
        IMPL
      )
    val symbols = Seq(
      si("tc/Show#", s.SymbolInformation.Kind.CLASS, "Show"),
      si("scala/package.List#", s.SymbolInformation.Kind.CLASS, "List"),
      si("scala/Int#", s.SymbolInformation.Kind.CLASS, "Int"),
      aParam,
      givenObj("tc/Show.intShow.", "intShow", tapp("tc/Show#", tref("scala/Int#"))),
      listShow
    )
    val az = Analyzer(index(doc("tc.scala", symbols, Nil)))
    val sym = TypeSymbol.from("tc/Show#").fold(fail(_), identity)
    val resolved =
      az.traceImplicitChain(sym, Some("Show[List[Int]]")).resolved.getOrElse(fail("no tree"))
    assertEquals(resolved.chosen.map(_.displayName), Some("listShow"))
    assertEquals(resolved.children.map(_.targetType), List("Show[Int]"))
    assertEquals(resolved.children.head.chosen.map(_.displayName), Some("intShow"))

  test("traceImplicitChain marks concrete applied type ambiguity"):
    def tapp(sym: String, args: s.Type*) = s.TypeRef(s.Type.Empty, sym, args)
    def givenObj(sym: String, display: String) =
      si(
        sym,
        s.SymbolInformation.Kind.OBJECT,
        display,
        s.ClassSignature(
          None,
          List(tapp("tc/Show#", tref("scala/Int#")), tref("java/lang/Object#")),
          s.Type.Empty,
          None
        ),
        IMPL
      )
    val symbols = Seq(
      si("tc/Show#", s.SymbolInformation.Kind.CLASS, "Show"),
      si("scala/Int#", s.SymbolInformation.Kind.CLASS, "Int"),
      givenObj("tc/Show.intShow1.", "intShow1"),
      givenObj("tc/Show.intShow2.", "intShow2")
    )
    val az = Analyzer(index(doc("amb.scala", symbols, Nil)))
    val sym = TypeSymbol.from("tc/Show#").fold(fail(_), identity)
    val resolved = az.traceImplicitChain(sym, Some("Show[Int]")).resolved.getOrElse(fail("no tree"))
    assert(resolved.ambiguous)
    assertEquals(resolved.chosen, None)
    assertEquals(resolved.candidates.map(_.target.displayName).toSet, Set("intShow1", "intShow2"))

  test("traceImplicitChain guards cycles in concrete applied type dependencies"):
    def tapp(sym: String, args: s.Type*) = s.TypeRef(s.Type.Empty, sym, args)
    def givenDef(sym: String, display: String, produces: s.Type, dependsOn: s.Type) =
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
                  s"$sym(p)",
                  s.SymbolInformation.Kind.PARAMETER,
                  "p",
                  s.ValueSignature(dependsOn),
                  IMPL
                )
              )
            )
          ),
          produces
        ),
        IMPL
      )
    val showInt = tapp("cy/Show#", tref("scala/Int#"))
    val eqInt = tapp("cy/Eq#", tref("scala/Int#"))
    val symbols = Seq(
      si("cy/Show#", s.SymbolInformation.Kind.CLASS, "Show"),
      si("cy/Eq#", s.SymbolInformation.Kind.CLASS, "Eq"),
      si("scala/Int#", s.SymbolInformation.Kind.CLASS, "Int"),
      givenDef("cy/showFromEq().", "showFromEq", showInt, eqInt),
      givenDef("cy/eqFromShow().", "eqFromShow", eqInt, showInt)
    )
    val az = Analyzer(index(doc("cycle.scala", symbols, Nil)))
    val sym = TypeSymbol.from("cy/Show#").fold(fail(_), identity)
    val resolved = az.traceImplicitChain(sym, Some("Show[Int]")).resolved.getOrElse(fail("no tree"))
    assertEquals(resolved.chosen.map(_.displayName), Some("showFromEq"))
    val eqNode = resolved.children.headOption.getOrElse(fail("missing Eq dependency"))
    assertEquals(eqNode.chosen.map(_.displayName), Some("eqFromShow"))
    val cycleNode = eqNode.children.headOption.getOrElse(fail("missing cycle marker"))
    assertEquals(cycleNode.targetType, "Show[Int]")
    assert(cycleNode.cycle)
