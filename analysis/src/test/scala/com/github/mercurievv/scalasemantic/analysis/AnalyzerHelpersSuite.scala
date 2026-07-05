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

  // renderType (every constructor shape, arbitrary nesting), renderConstant (every constant
  // kind, arbitrary values), and renderMethod (type params/implicit lists/separators, arbitrary
  // names and arities) are now covered as properties in AnalyzerHelpersPropertySuite — the fixed
  // examples that used to live here enumerated exactly the constructor shapes those properties
  // now generate generatively.

  test("rangeContains is inclusive at start, exclusive at end"):
    val r = s.Range(1, 2, 3, 4)
    assert(h.rangeContains(r, 1, 2), "start boundary is contained")
    assert(h.rangeContains(r, 2, 9), "an interior line is contained")
    assert(h.rangeContains(r, 3, 3), "just before the end is contained")
    assert(!h.rangeContains(r, 1, 1), "before the start char is excluded")
    assert(!h.rangeContains(r, 0, 9), "an earlier line is excluded")
    assert(!h.rangeContains(r, 3, 4), "the end position itself is excluded")
    assert(!h.rangeContains(r, 4, 0), "a later line is excluded")

  // rangeSpan's line-dominant formula and non-negativity are now covered as properties in
  // AnalyzerHelpersPropertySuite over any well-formed range.

  // Kept as concrete examples (rather than folded into the general alreadyAscribed property in
  // AnalyzerHelpersPropertySuite): these exercise realistic Scala syntax shapes (a param list, a
  // type-param list) that a synthetic bracket/colon/equals generator wouldn't reliably produce.
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
    assertEquals(hi.kindName("a/Dog#"), SymbolKind.Class)
    assertEquals(hi.kindName("a/Missing#"), SymbolKind.Unknown)

  test("linearize and knownSubtypes walk the parent relation"):
    assertEquals(hi.linearize("a/Dog#"), List("a/Animal#"))
    assertEquals(hi.knownSubtypes("a/Animal#"), List("a/Dog#"))
    assertEquals(hi.directParents(dogInfo), List("a/Animal#"))

  // --- more alreadyAscribed / rangeContains boundaries ----------------------

  test("alreadyAscribed: no-colon line, '=' before ':', and a zero start"):
    assert(!h.alreadyAscribed("val x", 0), "scanned to end with no colon/equals -> not ascribed")
    assert(!h.alreadyAscribed("a = b: c", 0), "a top-level '=' stops the scan before the later ':'")
    assert(h.alreadyAscribed("x: Int", 0), "a colon at start position counts (nameStart == 0)")

  test("rangeContains: a char past the start on the start line, before the end, is inside"):
    val r = s.Range(1, 2, 3, 4)
    assert(h.rangeContains(r, 1, 5), "same line as start, char beyond startCharacter -> contained")

  // --- localTypeText --------------------------------------------------------

  test("localTypeText renders the type, or '?' when SemanticDB recorded none"):
    val typed = sigInfo("local0", s.SymbolInformation.Kind.LOCAL, "a", s.ValueSignature(IntT))
    val untyped = sigInfo("local1", s.SymbolInformation.Kind.LOCAL, "b", s.NoSignature)
    val lh = AnalyzerHelpers(
      new SemanticIndex(Vector(s.TextDocument(uri = "l.scala", symbols = Vector(typed, untyped))))
    )
    assertEquals(lh.localTypeText("local0"), "Int")
    assertEquals(lh.localTypeText("local1"), "?", "an unascribed local has no recorded type")

  // --- linearize de-duplicates a diamond ------------------------------------

  test("linearize de-duplicates a diamond (a shared ancestor appears once)"):
    def cls(sym: String, display: String, parents: String*) =
      sigInfo(
        sym,
        s.SymbolInformation.Kind.CLASS,
        display,
        s.ClassSignature(None, parents.toList.map(ref(_)), s.Type.Empty, None)
      )
    // A extends B, C ; B extends D ; C extends D — D is reachable two ways.
    val dia = new SemanticIndex(
      Vector(
        s.TextDocument(
          uri = "d.scala",
          symbols = Vector(
            cls("d/A#", "A", "d/B#", "d/C#"),
            cls("d/B#", "B", "d/D#"),
            cls("d/C#", "C", "d/D#"),
            cls("d/D#", "D")
          )
        )
      )
    )
    val lin = AnalyzerHelpers(dia).linearize("d/A#")
    assertEquals(lin.distinct, lin, s"no duplicate ancestors: $lin")
    assertEquals(lin.toSet, Set("d/B#", "d/C#", "d/D#"))

  // --- implicitsProducing ---------------------------------------------------
  //
  // isGivenDefinition's implicit-bit-AND-kind law is now covered as a property in
  // AnalyzerHelpersPropertySuite, over every SymbolInformation.Kind.

  test("implicitsProducing returns only givens whose produced type matches"):
    val IMPL = s.SymbolInformation.Property.IMPLICIT.value
    val inst = sigInfo(
      "g/inst.",
      s.SymbolInformation.Kind.OBJECT,
      "inst",
      s.ClassSignature(None, List(ref("g/T#"), ref("java/lang/Object#")), s.Type.Empty, None)
    ).copy(properties = IMPL)
    // a plain (non-implicit) class that also "extends" T — must NOT be reported as a producer.
    val plain = sigInfo(
      "g/Plain#",
      s.SymbolInformation.Kind.CLASS,
      "Plain",
      s.ClassSignature(None, List(ref("g/T#")), s.Type.Empty, None)
    )
    val tpe = sigInfo("g/T#", s.SymbolInformation.Kind.TRAIT, "T")
    val gh = AnalyzerHelpers(
      new SemanticIndex(
        Vector(s.TextDocument(uri = "g.scala", symbols = Vector(inst, plain, tpe)))
      )
    )
    assertEquals(gh.implicitsProducing("g/T#").map(_.symbol), List("g/inst."))

  test("producedType unwraps the synthetic self-class of a `given ... with`"):
    val IMPL = s.SymbolInformation.Property.IMPLICIT.value
    // given def `foo` whose result is the synthetic self-class `foo#`, which extends Show.
    val givenDef = sigInfo(
      "gw/foo().",
      s.SymbolInformation.Kind.METHOD,
      "foo",
      s.MethodSignature(None, Nil, ref("gw/foo#"))
    ).copy(properties = IMPL)
    val selfClass = sigInfo(
      "gw/foo#",
      s.SymbolInformation.Kind.CLASS,
      "foo", // same display name as the given -> recognised as the self-class
      s.ClassSignature(None, List(ref("gw/Show#"), ref("java/lang/Object#")), s.Type.Empty, None)
    )
    val show = sigInfo("gw/Show#", s.SymbolInformation.Kind.TRAIT, "Show")
    val wh = AnalyzerHelpers(
      new SemanticIndex(
        Vector(s.TextDocument(uri = "gw.scala", symbols = Vector(givenDef, selfClass, show)))
      )
    )
    // the produced type is the interface Show, not the synthetic self-class foo
    assertEquals(wh.parentSymbol(wh.producedType(givenDef)), Some("gw/Show#"))
    assertEquals(wh.implicitsProducing("gw/Show#").map(_.symbol), List("gw/foo()."))
