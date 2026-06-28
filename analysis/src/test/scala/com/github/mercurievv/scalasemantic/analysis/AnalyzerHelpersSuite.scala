package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Unit tests for the stateless rendering/lookup helpers in [[AnalyzerHelpers]]. Most are pure
  * functions of hand-built SemanticDB protobufs (no on-disk index needed); `displayName` falls back
  * to a global symbol's last descriptor name, so fabricated symbols like `scala/Int#` render as
  * `Int`. A small in-memory index covers the few helpers that read symbol info. Registered in
  * stryker4s.conf so the printer/range/glob mutants are killed.
  */
class AnalyzerHelpersSuite extends munit.FunSuite:

  private val emptyIndex = SemanticIndex.fromRoots(Nil)
  private val h = AnalyzerHelpers(emptyIndex)

  private def ref(symbol: String, args: s.Type*): s.Type =
    s.TypeRef(s.Type.Empty, symbol, args.toList)
  private val IntT = ref("scala/Int#")
  private val StringT = ref("scala/Predef.String#")

  test("renderType: TypeRef with and without type arguments"):
    assertEquals(h.renderType(IntT), "Int")
    assertEquals(h.renderType(ref("scala/collection/immutable/List#", IntT)), "List[Int]")
    assertEquals(h.renderType(ref("scala/Predef.Map#", IntT, StringT)), "Map[Int, String]")

  test("renderType: every non-TypeRef shape and the empty default"):
    assertEquals(h.renderType(s.SingleType(s.Type.Empty, "a/B.")), "B.type")
    assertEquals(h.renderType(s.ThisType("a/B#")), "B.this")
    assertEquals(h.renderType(s.SuperType(s.Type.Empty, "a/B#")), "B")
    assertEquals(h.renderType(s.ByNameType(IntT)), "=> Int")
    assertEquals(h.renderType(s.RepeatedType(IntT)), "Int*")
    assertEquals(h.renderType(s.WithType(List(IntT, StringT))), "Int with String")
    assertEquals(h.renderType(s.IntersectionType(List(IntT, StringT))), "Int & String")
    assertEquals(h.renderType(s.UnionType(List(IntT, StringT))), "Int | String")
    assertEquals(h.renderType(s.AnnotatedType(Nil, IntT)), "Int")
    assertEquals(h.renderType(s.ExistentialType(IntT, None)), "Int")
    assertEquals(h.renderType(s.UniversalType(None, IntT)), "Int")
    assertEquals(h.renderType(s.StructuralType(IntT, None)), "Int")
    assertEquals(h.renderType(s.Type.Empty), "")

  test("renderType: ConstantType delegates to renderConstant"):
    assertEquals(h.renderType(s.ConstantType(s.IntConstant(42))), "42")

  test("renderConstant: every constant kind"):
    assertEquals(h.renderConstant(s.IntConstant(42)), "42")
    assertEquals(h.renderConstant(s.LongConstant(7L)), "7L")
    assertEquals(h.renderConstant(s.FloatConstant(1.5f)), "1.5f")
    assertEquals(h.renderConstant(s.DoubleConstant(2.5)), "2.5")
    assertEquals(h.renderConstant(s.BooleanConstant(true)), "true")
    assertEquals(h.renderConstant(s.CharConstant('a'.toInt)), "'a'")
    assertEquals(h.renderConstant(s.StringConstant("x")), "\"x\"")
    assertEquals(h.renderConstant(s.ShortConstant(3)), "3")
    assertEquals(h.renderConstant(s.ByteConstant(4)), "4")
    assertEquals(h.renderConstant(s.UnitConstant()), "Unit")
    assertEquals(h.renderConstant(s.NullConstant()), "null")

  test("renderMethod: type params, implicit lists, separators"):
    def p(name: String, tpe: String) = Parameter(name, tpe, isImplicit = false)
    assertEquals(
      h.renderMethod("f", Nil, List(ParameterList(List(p("x", "Int")), isImplicit = false)), "Int"),
      "def f(x: Int): Int"
    )
    assertEquals(
      h.renderMethod(
        "g",
        List("A", "B"),
        List(ParameterList(List(p("x", "A"), p("y", "B")), isImplicit = false)),
        "B"
      ),
      "def g[A, B](x: A, y: B): B"
    )
    assertEquals(
      h.renderMethod("e", Nil, List(ParameterList(List(p("c", "C")), isImplicit = true)), "Unit"),
      "def e(implicit c: C): Unit"
    )

  test("rangeContains is inclusive at start, exclusive at end"):
    val r = s.Range(1, 2, 3, 4)
    assert(h.rangeContains(r, 1, 2), "start boundary is contained")
    assert(h.rangeContains(r, 2, 9), "an interior line is contained")
    assert(h.rangeContains(r, 3, 3), "just before the end is contained")
    assert(!h.rangeContains(r, 1, 1), "before the start char is excluded")
    assert(!h.rangeContains(r, 0, 9), "an earlier line is excluded")
    assert(!h.rangeContains(r, 3, 4), "the end position itself is excluded")
    assert(!h.rangeContains(r, 4, 0), "a later line is excluded")

  test("rangeSpan weights lines over characters"):
    assertEquals(h.rangeSpan(s.Range(1, 0, 3, 5)), 20005)
    assertEquals(h.rangeSpan(s.Range(2, 4, 2, 9)), 5)

  test("alreadyAscribed finds a top-level colon before the body's ="):
    assert(h.alreadyAscribed("val x: Int = 1", 4), "explicit ascription")
    assert(!h.alreadyAscribed("val x = 1", 4), "no ascription")
    assert(!h.alreadyAscribed("def f(a: Int) = a", 4), "colon nested in () does not count")
    assert(h.alreadyAscribed("def f[T]: T = x", 4), "colon after [] group counts")
    assert(!h.alreadyAscribed("val x = 1", -1), "negative start")
    assert(!h.alreadyAscribed("val x = 1", 99), "start past end")

  test("packageDotted and joinFqn"):
    assertEquals(h.packageDotted("com/foo/bar/"), "com.foo.bar")
    assertEquals(h.packageDotted(""), "")
    assertEquals(h.joinFqn("", "X"), "X")
    assertEquals(h.joinFqn("com.foo", "X"), "com.foo.X")

  test("globMatcher: None keeps all; literal is substring; * is wildcard"):
    assert(h.globMatcher(None)("anything"))
    val fooGlob = h.globMatcher(Some("Foo"))
    assert(fooGlob("xFooy"))
    assert(!fooGlob("bar"))
    val abGlob = h.globMatcher(Some("a*b"))
    assert(abGlob("axxb"))
    assert(abGlob("ab"))
    assert(!abGlob("ba"))

  test("parentSymbol and notObject"):
    assertEquals(h.parentSymbol(IntT), Some("scala/Int#"))
    assertEquals(h.parentSymbol(s.SingleType(s.Type.Empty, "a/B.")), Some("a/B."))
    assertEquals(h.parentSymbol(s.Type.Empty), None)
    assert(h.notObject(IntT), "Int is not Object")
    assert(!h.notObject(ref("java/lang/Object#")), "Object is Object")

  test("isImplicit reads the IMPLICIT property bit"):
    def info(props: Int) =
      s.SymbolInformation(symbol = "a/x.", properties = props)
    assert(h.isImplicit(info(s.SymbolInformation.Property.IMPLICIT.value)))
    assert(!h.isImplicit(info(0)))
    assert(
      h.isImplicit(info(s.SymbolInformation.Property.IMPLICIT.value | 1)),
      "bit set among others"
    )

  test("directParents reads a ClassSignature's parents; non-class is empty"):
    val cls = s.SymbolInformation(
      symbol = "a/Dog#",
      kind = s.SymbolInformation.Kind.CLASS,
      signature =
        s.ClassSignature(None, List(ref("a/Animal#"), ref("java/lang/Object#")), s.Type.Empty, None)
    )
    assertEquals(h.directParents(cls), List("a/Animal#", "java/lang/Object#"))
    val notClass = s.SymbolInformation(symbol = "a/x.", signature = s.NoSignature)
    assertEquals(h.directParents(notClass), Nil)

  // --- helpers that read a small in-memory index ---------------------------

  private def sigInfo(
      symbol: String,
      kind: s.SymbolInformation.Kind,
      displayName: String,
      signature: s.Signature = s.NoSignature
  ): s.SymbolInformation =
    s.SymbolInformation(
      symbol = symbol,
      kind = kind,
      displayName = displayName,
      signature = signature
    )

  private val dogInfo = sigInfo(
    "a/Dog#",
    s.SymbolInformation.Kind.CLASS,
    "Dog",
    s.ClassSignature(None, List(ref("a/Animal#")), s.Type.Empty, None)
  )
  private val animalInfo = sigInfo("a/Animal#", s.SymbolInformation.Kind.CLASS, "Animal")
  private val initInfo = sigInfo("a/Dog#`<init>`().", s.SymbolInformation.Kind.METHOD, "<init>")
  private val fieldInfo = sigInfo("a/Dog#x.", s.SymbolInformation.Kind.FIELD, "x")
  private val hiddenInfo = sigInfo("a/Dog#h.", s.SymbolInformation.Kind.PARAMETER, "h")
  private val idx =
    new SemanticIndex(
      Vector(
        s.TextDocument(
          uri = "a.scala",
          symbols = Vector(dogInfo, animalInfo, initInfo, fieldInfo, hiddenInfo)
        )
      )
    )
  private val hi = AnalyzerHelpers(idx)

  test("includeInOutline keeps named class/field, drops <init> and non-outline kinds"):
    assert(hi.includeInOutline("a/Dog#"), "class")
    assert(hi.includeInOutline("a/Dog#x."), "field")
    assert(!hi.includeInOutline("a/Dog#`<init>`()."), "constructor excluded by name")
    assert(!hi.includeInOutline("a/Dog#h."), "parameter kind excluded")
    assert(!hi.includeInOutline("a/Missing#"), "unknown symbol excluded")

  test("kindName returns the kind or UNKNOWN"):
    assertEquals(hi.kindName("a/Dog#"), "CLASS")
    assertEquals(hi.kindName("a/Missing#"), "UNKNOWN")

  test("linearize and knownSubtypes walk the parent relation"):
    assertEquals(hi.linearize("a/Dog#"), List("a/Animal#"))
    assertEquals(hi.knownSubtypes("a/Animal#"), List("a/Dog#"))
    assertEquals(hi.directParents(dogInfo), List("a/Animal#"))
