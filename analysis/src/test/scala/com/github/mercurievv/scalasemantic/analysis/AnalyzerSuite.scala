package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.model.MoveImport
import com.github.mercurievv.scalasemantic.model.SymbolKind
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Phase 3: query-engine tests against the `com.github.mercurievv.scalasemantic.fixtures`
  * SemanticDB. Several of these exercise relationships (known subtypes, implicit param lists) that
  * Metals cannot answer from a single symbol query.
  */
class AnalyzerSuite extends munit.FunSuite:

  private val az = Analyzer(SemanticIndex.fromProject("."))

  private val Animal = "com/github/mercurievv/scalasemantic/fixtures/Animal#"
  private val Dog = "com/github/mercurievv/scalasemantic/fixtures/Dog#"
  private val Fish = "com/github/mercurievv/scalasemantic/fixtures/Fish#"
  private val Robot = "com/github/mercurievv/scalasemantic/fixtures/Robot#"
  private val Greeter = "com/github/mercurievv/scalasemantic/fixtures/Greeter#"
  private val Render = "com/github/mercurievv/scalasemantic/fixtures/Sample.render()."
  private val Over = "com/github/mercurievv/scalasemantic/fixtures/Sample.over()."
  private val Show = "com/github/mercurievv/scalasemantic/fixtures/Show#"
  private val Eq = "com/github/mercurievv/scalasemantic/fixtures/Eq#"
  private val IntShow = "com/github/mercurievv/scalasemantic/fixtures/Sample.intShow."
  private val ListShow = "com/github/mercurievv/scalasemantic/fixtures/Sample.listShow()."
  private val IntEq = "com/github/mercurievv/scalasemantic/fixtures/Sample.intEq."
  private val OverloadChildFoo = "com/github/mercurievv/scalasemantic/fixtures/OverloadChild#foo()."
  private def calls(m: String) = s"com/github/mercurievv/scalasemantic/fixtures/Calls.$m()."

  private def sym(value: String): SemanticDbSymbol =
    SemanticDbSymbol.from(value).fold(fail(_), identity)
  private def tpe(value: String): TypeSymbol =
    TypeSymbol.from(value).fold(fail(_), identity)
  private def meth(value: String): MethodSymbol =
    MethodSymbol.from(value).fold(fail(_), identity)
  private def pkg(value: String): PackageSymbol =
    PackageSymbol.from(value).fold(fail(_), identity)
  private def uri(value: String): DocumentUri =
    DocumentUri.from(value).fold(fail(_), identity)
  private def pos(line: Int, character: Int): SourcePosition =
    SourcePosition.from(line, character).fold(fail(_), identity)

  test("findUsages reports the definition and cross-type references of a trait") {
    val u = az.findUsages(sym(Animal))
    assert(u.definitions.nonEmpty, "Animal should have a definition occurrence")
    // Dog and Fish both `extends Animal` → reference occurrences in other positions.
    assert(u.references.nonEmpty, "Animal should be referenced by its subtypes")
  }

  // Cross-build trait: compat-fixtures emits SemanticDB under both scala-3 and scala-2.13, and the
  // same .semanticdb can be present in more than one target dir — exercises dedup + path scoping.
  // `VirtualBase` (CallGraph.scala) is identically defined in both compat-fixtures version trees.
  private val CompatVirtualBase = "com/github/mercurievv/scalasemantic/compat/VirtualBase#"

  test("findSymbol kind filters to a single SymbolInformation kind") {
    val traits = az.findSymbol("VirtualBase", kind = Some("TRAIT")).map(_.symbol).toSet
    assertEquals(traits, Set(CompatVirtualBase))
    assert(az.findSymbol("VirtualBase", kind = Some("TRAIT")).forall(_.kind == SymbolKind.Trait))
  }

  test("findSymbol exact matches the whole display name only") {
    val exact = az.findSymbol("VirtualBase", exact = true).map(_.displayName)
    assert(exact.forall(_ == "VirtualBase"), exact.toString)
    assert(
      az.findSymbol("VirtualBas", exact = true).isEmpty,
      "no symbol is named exactly 'VirtualBas'"
    )
  }

  test("findSymbol pathFilter scopes by the symbol's definition uri") {
    val scoped = az.findSymbol("VirtualBase", kind = Some("TRAIT"), pathFilter = Some("*compat*"))
    assertEquals(scoped.map(_.symbol), List(CompatVirtualBase))
  }

  test("findUsages dedups occurrence locations (no repeated uri:line:col)") {
    val u = az.findUsages(sym(CompatVirtualBase))
    assertEquals(u.definitions, u.definitions.distinct, "definitions must be unique")
    assertEquals(u.references, u.references.distinct, "references must be unique")
    // golden counts after dedup: 2 defs (scala-3 + scala-2.13); VirtualBase is referenced by
    // VirtualImpl1/VirtualImpl2 (extends) and callVirtual's param type — 3 refs per build.
    assertEquals(u.definitions.size, 2, u.definitions.map(_.uri).toString)
    assertEquals(u.references.size, 6, u.references.map(_.uri).toString)
  }

  test("findUsages pathFilter scopes occurrences to matching document uris") {
    val all = az.findUsages(sym(CompatVirtualBase))
    val scoped = az.findUsages(sym(CompatVirtualBase), Some("*scala-3*"))
    assert(scoped.references.size < all.references.size, "filter must drop non-matching refs")
    assert(
      scoped.references.forall(_.uri.contains("scala-3")),
      scoped.references.map(_.uri).toString
    )
    assert(
      scoped.definitions.forall(_.uri.contains("scala-3")),
      scoped.definitions.map(_.uri).toString
    )
    // golden: only the scala-3 build remains — 1 def, 3 refs.
    assertEquals(scoped.definitions.size, 1)
    assertEquals(scoped.references.size, 3)
  }

  test("findUsages with no pathFilter is unchanged (None keeps everything)") {
    assertEquals(az.findUsages(sym(CompatVirtualBase), None), az.findUsages(sym(CompatVirtualBase)))
  }

  test("methodSignature captures type params and an implicit/using parameter list") {
    val sig =
      az.methodSignature(meth(Render)).getOrElse(fail("render should have a method signature"))
    assertEquals(sig.typeParameters, List("A"))
    assertEquals(sig.parameterLists.size, 2)
    assertEquals(sig.parameterLists(0).isImplicit, false)
    assertEquals(sig.parameterLists(1).isImplicit, true)
    assertEquals(sig.parameterLists(1).parameters.map(_.name), List("sh"))
    assertEquals(sig.parameterLists(1).parameters.head.tpe, "Show[A]")
    assertEquals(sig.returnType, "String")
    assertEquals(sig.rendered, "def render[A](a: A)(implicit sh: Show[A]): String")
  }

  test("MethodSymbol rejects non-methods") {
    assert(MethodSymbol.from(Animal).isLeft)
  }

  test("classHierarchy pathFilter scopes related types by their definition uri") {
    val all = az.classHierarchy(tpe(Animal)).getOrElse(fail("Animal hierarchy"))
    assertEquals(all.knownSubtypes.map(_.symbol), List(Dog, Fish))
    // Dog/Fish are defined under fixtures, so a compat-scoped glob drops them.
    val compat =
      az.classHierarchy(tpe(Animal), Some("*compat*")).getOrElse(fail("Animal hierarchy"))
    assert(compat.knownSubtypes.isEmpty, compat.knownSubtypes.map(_.symbol).toString)
    val fixtures =
      az.classHierarchy(tpe(Animal), Some("*fixtures*")).getOrElse(fail("Animal hierarchy"))
    assertEquals(fixtures.knownSubtypes.map(_.symbol), List(Dog, Fish))
  }

  test("classHierarchy with no pathFilter is unchanged (None keeps everything)") {
    assertEquals(az.classHierarchy(tpe(Animal), None), az.classHierarchy(tpe(Animal)))
  }

  test("members pathFilter scopes members by their definition uri") {
    // Robot inherits `greet` from Greeter (both in fixtures).
    val all = az.members(tpe(Robot)).getOrElse(fail("Robot members"))
    assert(
      all.inherited.exists(_.displayName == "greet"),
      all.inherited.map(_.displayName).toString
    )
    val compat = az.members(tpe(Robot), Some("*compat*")).getOrElse(fail("Robot members"))
    assert(
      compat.declared.isEmpty && compat.inherited.isEmpty,
      "no Robot members live under compat"
    )
    val fixtures = az.members(tpe(Robot), Some("*fixtures*")).getOrElse(fail("Robot members"))
    assert(fixtures.inherited.exists(_.displayName == "greet"))
  }

  test("members with no pathFilter is unchanged (None keeps everything)") {
    assertEquals(az.members(tpe(Robot), None), az.members(tpe(Robot)))
  }

  test("classHierarchy lists direct parents and transitive linearization") {
    val h = az.classHierarchy(tpe(Dog)).getOrElse(fail("Dog should have a class signature"))
    assert(h.parents.map(_.symbol).contains(Animal), s"Dog parents: ${h.parents.map(_.symbol)}")
    assert(h.linearization.map(_.symbol).contains(Animal), "Animal in Dog linearization")
  }

  test("classHierarchy finds known subtypes across the index (beyond a single-symbol query)") {
    val h = az.classHierarchy(tpe(Animal)).getOrElse(fail("Animal should have a class signature"))
    assertEquals(h.knownSubtypes.map(_.symbol), List(Dog, Fish))
  }

  test("findOverloads groups all methods sharing an owner and name") {
    val o = az.findOverloads(meth(Over))
    assertEquals(o.name, "over")
    assertEquals(
      o.overloads.map(_.rendered).toSet,
      Set("def over(x: Int): Int", "def over(x: String): String")
    )
  }

  test("findOverloads also lists same-named methods inherited from a parent type") {
    val o = az.findOverloads(meth(OverloadChildFoo))
    assertEquals(o.name, "foo")
    assertEquals(
      o.overloads.map(_.rendered).toSet,
      Set("def foo(x: Int): Int", "def foo(x: String): String")
    )
    assertEquals(
      o.inheritedOverloads.map(_.rendered),
      List("def foo(x: Long): Long  (from OverloadParent)")
    )
  }

  test("members separates locally declared from inherited (non-overridden) members") {
    val dog = az.members(tpe(Dog)).getOrElse(fail("Dog should have members"))
    assert(dog.declared.map(_.displayName).contains("fetch"), dog.declared.toString)
    // Dog overrides `name`, so nothing concrete is left to inherit from Animal.
    assertEquals(dog.inherited, Nil)

    val robot = az.members(tpe(Robot)).getOrElse(fail("Robot should have members"))
    assertEquals(robot.inherited.map(_.displayName), List("greet"))
    assertEquals(robot.inherited.head.declaredIn.symbol, Greeter)
  }

  test("typeAtPosition resolves the symbol at a definition's own location") {
    val defLoc =
      az.findUsages(sym(Dog)).definitions.headOption.getOrElse(fail("Dog needs a definition"))
    val at = az
      .typeAtPosition(uri(defLoc.uri), pos(defLoc.range.start.line, defLoc.range.start.character))
      .getOrElse(fail("expected a symbol at Dog's definition position"))
    assertEquals(at.symbol, Dog)
    assertEquals(at.displayName, "Dog")
  }

  test("typeAtPosition returns None for an unknown document") {
    assertEquals(az.typeAtPosition(uri("does/not/exist.scala"), pos(0, 0)), None)
  }

  test("resolveImplicits lists given definitions producing a type, ignoring params/synthetics") {
    val r = az.resolveImplicits(tpe(Show))
    assertEquals(r.candidates.map(_.target.symbol), List(IntShow, ListShow))
    assertEquals(r.candidates.map(_.tpe).toSet, Set("Show[Int]", "Show[List[A]]"))
    assertEquals(r.chosen, None) // two candidates → ambiguous
  }

  test("resolveImplicits picks `chosen` when exactly one given matches") {
    val r = az.resolveImplicits(tpe(Eq))
    assertEquals(r.candidates.map(_.target.symbol), List(IntEq))
    assertEquals(r.chosen.map(_.symbol), Some(IntEq))
  }

  test("traceImplicitChain records implicit dependencies of each given") {
    val steps = az.traceImplicitChain(tpe(Show)).steps
    assertEquals(steps.map(_.target.symbol), List(IntShow, ListShow))
    val listStep = steps.find(_.target.symbol == ListShow).getOrElse(fail("listShow step missing"))
    assertEquals(listStep.dependsOn, List(Show)) // Show[List[A]] needs a Show[A]
    val intStep = steps.find(_.target.symbol == IntShow).getOrElse(fail("intShow step missing"))
    assertEquals(intStep.dependsOn, Nil)
  }

  test("callPath finds a transitive call chain with its edges") {
    val p = az.callPath(meth(calls("a")), meth(calls("c")))
    assertEquals(p.path.map(_.displayName), List("a", "b", "c"))
    assertEquals(
      p.edges.map(e => e.from.displayName -> e.to.displayName),
      List("a" -> "b", "b" -> "c")
    )
  }

  test("callPath returns an empty path when the target is unreachable") {
    // c calls nothing, so a is not reachable from c.
    assertEquals(az.callPath(meth(calls("c")), meth(calls("a"))).path, Nil)
  }

  test("movePlan relocates a symbol and rewrites its FQN, keeping every usage") {
    val p = az.movePlan(sym(Animal), pkg("com/github/mercurievv/scalasemantic/relocated/"))
    assertEquals(p.fromFqn, "com.github.mercurievv.scalasemantic.fixtures.Animal")
    assertEquals(p.toFqn, "com.github.mercurievv.scalasemantic.relocated.Animal")
    assert(p.definition.nonEmpty, "Animal should have a definition to relocate")
    // Dog and Fish `extends Animal` → the move covers calls/usages, not just the body.
    assert(p.references.nonEmpty, "the move must list every reference")
  }

  test("movePlan emits a per-file import swap for cross-package references") {
    // Two files, two packages: Foo defined in pkgA, referenced from a file in pkgB.
    def occ(sym: String, role: s.SymbolOccurrence.Role, line: Int) =
      s.SymbolOccurrence(symbol = sym, role = role, range = Some(s.Range(line, 0, line, 3)))
    val docA = s.TextDocument(
      uri = "a/Foo.scala",
      occurrences = Seq(occ("pkgA/Foo#", s.SymbolOccurrence.Role.DEFINITION, 0))
    )
    val docB = s.TextDocument(
      uri = "b/Bar.scala",
      occurrences = Seq(
        occ("pkgB/Bar#", s.SymbolOccurrence.Role.DEFINITION, 0),
        occ("pkgA/Foo#", s.SymbolOccurrence.Role.REFERENCE, 1)
      )
    )
    val mz = new Analyzer(new SemanticIndex(Vector(docA, docB)))
    val p = mz.movePlan(sym("pkgA/Foo#"), pkg("pkgC/"))
    // the definition's own file moves with it (no import edit there); only Bar.scala needs one
    assertEquals(p.imports, List(MoveImport("b/Bar.scala", "pkgA.Foo", "pkgC.Foo")))
  }

  // --- related product-record usages (#286) -----------------------------------

  private val Order = "com/github/mercurievv/scalasemantic/fixtures/Order#"

  test("findUsages surfaces a case class's construction sites, which are not type references") {
    val u = az.findUsages(sym(Order))
    val kinds = u.related.map(_.kind).toSet
    // `Order(1, "widget")` resolves to the companion object symbol, so it is absent from
    // `references` entirely — this is the whole point of the related section.
    assert(kinds.contains("companion"), s"expected a companion group, got $kinds")
    assert(
      u.related.filter(_.kind == "companion").forall(_.locations.nonEmpty),
      "a related group is only emitted when it has locations"
    )
  }

  test("findUsages relates copy and parameter accessors of a case class") {
    val kinds = az.findUsages(sym(Order)).related.map(_.kind).toSet
    assert(kinds.contains("copy"), s"expected a copy group, got $kinds")
    assert(kinds.contains("accessors"), s"expected an accessors group, got $kinds")
  }

  test("findUsages relates nothing for a type that is not case-like") {
    assertEquals(az.findUsages(sym(Animal)).related, Nil)
    assertEquals(az.findUsages(sym(Dog)).related, Nil)
  }

  test("findUsages related kinds can be narrowed, and an empty set drops the section") {
    val only = az.findUsages(sym(Order), None, Some(Set("companion")))
    assertEquals(only.related.map(_.kind).distinct, List("companion"))
    assertEquals(az.findUsages(sym(Order), None, Some(Set.empty)).related, Nil)
  }

  test("findUsages related groups honour pathFilter") {
    val scoped = az.findUsages(sym(Order), Some("*nowhere*"))
    assertEquals(scoped.related, Nil)
  }

  test("findUsages dogfoods related expansion on this project's own UsagesResult") {
    // The motivating case from #286: `UsagesResult(...)` is built inside `Analyzer.findUsages`,
    // and that site resolves to the companion object — so the class symbol alone never sees it.
    val self = sym("com/github/mercurievv/scalasemantic/model/UsagesResult#")
    val companion = az.findUsages(self).related.filter(_.kind == "companion")
    assert(companion.nonEmpty, "UsagesResult should relate to its companion object")
    val uris = companion.flatMap(_.locations).map(_.uri)
    assert(uris.exists(_.endsWith("Analyzer.scala")), s"construction site not found, saw $uris")
    // …and that site is genuinely absent from the plain type-reference answer.
    val plain = az.findUsages(self, None, Some(Set.empty))
    val plainLocs = (plain.definitions ++ plain.references).toSet
    assert(
      companion.flatMap(_.locations).exists(!plainLocs.contains(_)),
      "related expansion must add sites the class symbol alone does not report"
    )
  }

  test("renamePlan of a case class rewrites its construction sites too") {
    val plan = az.renamePlan(sym(Order), ScalaIdentifier.from("Purchase").fold(fail(_), identity))
    val classOnly = az.findUsages(sym(Order), None, Some(Set.empty))
    assert(
      plan.editCount > classOnly.definitions.size + classOnly.references.size,
      s"rename must cover the companion/constructor sites, got ${plan.editCount} edits"
    )
    assert(plan.edits.forall(_.newText == "Purchase"))
  }
