package com.github.mercurievv.scalasemantic.model

import upickle.default.read
import upickle.default.write

/** Phase 2: confirm every result model derives a working upickle codec and round-trips. */
class ModelsSuite extends munit.FunSuite:

  private def roundTrip[A: upickle.default.ReadWriter](value: A)(using
      loc: munit.Location
  ): Unit =
    assertEquals(read[A](write(value)), value)

  private val loc = Location("file:///A.scala", Range(Position(1, 2), Position(1, 8)))
  private val ref = SymbolRef("a/B#", "B", "CLASS")

  test("locations and symbol refs round-trip") {
    roundTrip(loc)
    roundTrip(ref)
  }

  test("method signature with implicit param list round-trips") {
    val sig = MethodSignature(
      symbol = "a/B#f().",
      displayName = "f",
      typeParameters = List("A"),
      parameterLists = List(
        ParameterList(List(Parameter("x", "scala/Int#", isImplicit = false)), isImplicit = false),
        ParameterList(List(Parameter("ev", "a/Show#", isImplicit = true)), isImplicit = true)
      ),
      returnType = "scala/Predef.String#",
      rendered = "def f[A](x: Int)(implicit ev: Show): String"
    )
    roundTrip(sig)
    roundTrip(OverloadsResult("f", List(sig)))
  }

  test("hierarchy, members, implicits, call graph round-trip") {
    roundTrip(ClassHierarchy("a/B#", "B", List(ref), List(ref), Nil))
    roundTrip(MembersResult("a/B#", "B", List(MemberInfo("a/B#f().", "f", "METHOD", ref)), Nil))
    roundTrip(
      ImplicitResolution("a/Show#", Some(ref), List(ImplicitCandidate(ref, "a/Show#", false)))
    )
    roundTrip(ImplicitChain("a/Show#", List(ImplicitChainStep(ref, "a/Show#", List("a/Eq#")))))
    roundTrip(TypeAtPosition(loc, "a/B#", "B", "a/B#"))
    roundTrip(CallGraphPath(ref, ref, List(ref), List(CallEdge(ref, ref, loc))))
  }

  test("result JSON is field-named, not positional") {
    assert(write(loc).contains("\"uri\""), write(loc))
    assert(write(ref).contains("\"displayName\""), write(ref))
  }
