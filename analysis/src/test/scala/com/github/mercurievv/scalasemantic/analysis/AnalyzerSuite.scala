package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

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
  private def calls(m: String) = s"com/github/mercurievv/scalasemantic/fixtures/Calls.$m()."

  test("findUsages reports the definition and cross-type references of a trait") {
    val u = az.findUsages(Animal)
    assert(u.definitions.nonEmpty, "Animal should have a definition occurrence")
    // Dog and Fish both `extends Animal` → reference occurrences in other positions.
    assert(u.references.nonEmpty, "Animal should be referenced by its subtypes")
  }

  test("methodSignature captures type params and an implicit/using parameter list") {
    val sig = az.methodSignature(Render).getOrElse(fail("render should have a method signature"))
    assertEquals(sig.typeParameters, List("A"))
    assertEquals(sig.parameterLists.size, 2)
    assertEquals(sig.parameterLists(0).isImplicit, false)
    assertEquals(sig.parameterLists(1).isImplicit, true)
    assertEquals(sig.parameterLists(1).parameters.map(_.name), List("sh"))
    assertEquals(sig.parameterLists(1).parameters.head.tpe, "Show[A]")
    assertEquals(sig.returnType, "String")
    assertEquals(sig.rendered, "def render[A](a: A)(implicit sh: Show[A]): String")
  }

  test("methodSignature returns None for non-methods") {
    assertEquals(az.methodSignature(Animal), None)
  }

  test("classHierarchy lists direct parents and transitive linearization") {
    val h = az.classHierarchy(Dog).getOrElse(fail("Dog should have a class signature"))
    assert(h.parents.map(_.symbol).contains(Animal), s"Dog parents: ${h.parents.map(_.symbol)}")
    assert(h.linearization.map(_.symbol).contains(Animal), "Animal in Dog linearization")
  }

  test("classHierarchy finds known subtypes across the index (beyond a single-symbol query)") {
    val h = az.classHierarchy(Animal).getOrElse(fail("Animal should have a class signature"))
    assertEquals(h.knownSubtypes.map(_.symbol), List(Dog, Fish))
  }

  test("findOverloads groups all methods sharing an owner and name") {
    val o = az.findOverloads(Over)
    assertEquals(o.name, "over")
    assertEquals(
      o.overloads.map(_.rendered).toSet,
      Set("def over(x: Int): Int", "def over(x: String): String")
    )
  }

  test("members separates locally declared from inherited (non-overridden) members") {
    val dog = az.members(Dog).getOrElse(fail("Dog should have members"))
    assert(dog.declared.map(_.displayName).contains("fetch"), dog.declared.toString)
    // Dog overrides `name`, so nothing concrete is left to inherit from Animal.
    assertEquals(dog.inherited, Nil)

    val robot = az.members(Robot).getOrElse(fail("Robot should have members"))
    assertEquals(robot.inherited.map(_.displayName), List("greet"))
    assertEquals(robot.inherited.head.declaredIn.symbol, Greeter)
  }

  test("typeAtPosition resolves the symbol at a definition's own location") {
    val defLoc = az.findUsages(Dog).definitions.headOption.getOrElse(fail("Dog needs a definition"))
    val at = az
      .typeAtPosition(defLoc.uri, defLoc.range.start.line, defLoc.range.start.character)
      .getOrElse(fail("expected a symbol at Dog's definition position"))
    assertEquals(at.symbol, Dog)
    assertEquals(at.displayName, "Dog")
  }

  test("typeAtPosition returns None for an unknown document") {
    assertEquals(az.typeAtPosition("file:///does/not/exist.scala", 0, 0), None)
  }

  test("resolveImplicits lists given definitions producing a type, ignoring params/synthetics") {
    val r = az.resolveImplicits(Show)
    assertEquals(r.candidates.map(_.target.symbol), List(IntShow, ListShow))
    assertEquals(r.candidates.map(_.tpe).toSet, Set("Show[Int]", "Show[List[A]]"))
    assertEquals(r.chosen, None) // two candidates → ambiguous
  }

  test("resolveImplicits picks `chosen` when exactly one given matches") {
    val r = az.resolveImplicits(Eq)
    assertEquals(r.candidates.map(_.target.symbol), List(IntEq))
    assertEquals(r.chosen.map(_.symbol), Some(IntEq))
  }

  test("traceImplicitChain records implicit dependencies of each given") {
    val steps = az.traceImplicitChain(Show).steps
    assertEquals(steps.map(_.target.symbol), List(IntShow, ListShow))
    val listStep = steps.find(_.target.symbol == ListShow).getOrElse(fail("listShow step missing"))
    assertEquals(listStep.dependsOn, List(Show)) // Show[List[A]] needs a Show[A]
    val intStep = steps.find(_.target.symbol == IntShow).getOrElse(fail("intShow step missing"))
    assertEquals(intStep.dependsOn, Nil)
  }

  test("callPath finds a transitive call chain with its edges") {
    val p = az.callPath(calls("a"), calls("c"))
    assertEquals(p.path.map(_.displayName), List("a", "b", "c"))
    assertEquals(
      p.edges.map(e => e.from.displayName -> e.to.displayName),
      List("a" -> "b", "b" -> "c")
    )
  }

  test("callPath returns an empty path when the target is unreachable") {
    // c calls nothing, so a is not reachable from c.
    assertEquals(az.callPath(calls("c"), calls("a")).path, Nil)
  }
