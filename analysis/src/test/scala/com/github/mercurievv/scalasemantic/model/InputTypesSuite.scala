package com.github.mercurievv.scalasemantic.model

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Unit tests for the boundary-validation smart constructors in [[InputTypes]]. These are pure (no
  * SemanticDB needed), so they are cheap to exercise exhaustively — both the accept and reject
  * paths, plus the off-by-one boundaries of the position/range comparisons. They run under
  * Stryker's test-filter (alongside ModelsSuite) to kill the otherwise-uncovered validation
  * mutants. A few invariants are checked with ScalaCheck where a property states the intent more
  * sharply than examples (position ordering, package normalization).
  */
class InputTypesSuite extends munit.ScalaCheckSuite:

  // Assert a Left whose message carries the given substring (kills StringLiteral mutations on the
  // error text, which survive when only `.isLeft` is checked).
  private def assertLeft(e: Either[String, ?], substring: String)(using munit.Location): Unit =
    assert(e.isLeft, s"expected Left, got $e")
    assert(e.swap.exists(_.contains(substring)), s"message ${e.swap} did not contain '$substring'")

  test("SemanticDbSymbol.from accepts global and local, rejects blank and non-symbol"):
    assert(SemanticDbSymbol.from("com/example/Foo#").isRight)
    assert(SemanticDbSymbol.from("local0").isRight)
    assertLeft(SemanticDbSymbol.from("   "), "non-empty")
    assertLeft(
      SemanticDbSymbol.from("   "),
      "SemanticDB symbol"
    ) // the field name is in the message
    assertLeft(SemanticDbSymbol.from("foo"), "invalid SemanticDB symbol")

  test("MethodSymbol.from accepts a method, rejects a type and a non-global"):
    assert(MethodSymbol.from("com/example/Foo#bar().").isRight)
    assertLeft(MethodSymbol.from("com/example/Foo#"), "expected method symbol")
    assertLeft(MethodSymbol.from("local0"), "expected method symbol")

  test("TypeSymbol.from accepts a type, rejects a method"):
    assert(TypeSymbol.from("com/example/Foo#").isRight)
    assertLeft(TypeSymbol.from("com/example/Foo#bar()."), "expected type symbol")

  test("PackageSymbol.from: blank -> empty, appends slash, rejects non-package"):
    assertEquals(PackageSymbol.from("").toOption.map(_.value), Some(""))
    assertEquals(PackageSymbol.from("  ").toOption.map(_.value), Some(""))
    assertEquals(PackageSymbol.from("com/example/").toOption.map(_.value), Some("com/example/"))
    assertEquals(PackageSymbol.from("com/example").toOption.map(_.value), Some("com/example/"))
    assertEquals(PackageSymbol.from("_root_/").toOption.map(_.value), Some("_root_/"))
    // non-package symbols are rejected: a type (#), a method (()), a multi (;), a dotted name (.),
    // and whitespace are all invalid package descriptors.
    assertLeft(PackageSymbol.from("com/Foo#"), "invalid package symbol")
    assertLeft(PackageSymbol.from("com/Foo#bar()."), "invalid package symbol")
    assertLeft(PackageSymbol.from("a;b"), "invalid package symbol")
    assertLeft(PackageSymbol.from("com.example"), "invalid package symbol")
    assertLeft(PackageSymbol.from("a b"), "invalid package symbol")
    assertLeft(PackageSymbol.from("com//example"), "invalid package symbol")

  test("DocumentUri.from: relative ok; rejects blank, absolute, and dot-dot"):
    assert(DocumentUri.from("src/Main.scala").isRight)
    assertLeft(DocumentUri.from("   "), "non-empty")
    assertLeft(DocumentUri.from("   "), "document uri") // the field name is in the message
    assertLeft(DocumentUri.from("/etc/passwd"), "must be relative")
    assertLeft(DocumentUri.from("a/../b.scala"), "must not contain")

  test("NonNegativeInt.from accepts >= 0, rejects negative"):
    assert(NonNegativeInt.from(0, "line").isRight)
    assert(NonNegativeInt.from(7, "line").isRight)
    assertLeft(NonNegativeInt.from(-1, "line"), "must be >= 0")

  test("PositiveInt.from accepts > 0, rejects 0 and negative; DefaultLimit is 50"):
    assert(PositiveInt.from(1, "limit").isRight)
    assertLeft(PositiveInt.from(0, "limit"), "must be > 0")
    assertLeft(PositiveInt.from(-3, "limit"), "must be > 0")
    assertEquals(PositiveInt.DefaultLimit.value, 50)

  test("SourcePosition.before respects line then character, strictly"):
    def pos(l: Int, c: Int) = SourcePosition.from(l, c).toOption.get
    assert(pos(1, 2).before(pos(1, 3)), "same line, later char")
    assert(pos(1, 2).before(pos(2, 0)), "earlier line wins")
    assert(!pos(1, 2).before(pos(1, 2)), "equal is not before")
    assert(!pos(1, 3).before(pos(1, 2)), "same line, earlier char")
    assert(!pos(2, 0).before(pos(1, 9)), "later line is not before")

  test("SourcePosition.atOrBefore is inclusive on the boundary"):
    def pos(l: Int, c: Int) = SourcePosition.from(l, c).toOption.get
    assert(pos(1, 2).atOrBefore(1, 2), "equal counts")
    assert(pos(1, 2).atOrBefore(1, 3), "same line, later char")
    assert(pos(1, 2).atOrBefore(2, 0), "earlier line")
    assert(!pos(1, 2).atOrBefore(1, 1), "same line, earlier char")
    assert(!pos(1, 2).atOrBefore(0, 9), "later line")

  test("SourcePosition.atOrAfter is inclusive on the boundary"):
    def pos(l: Int, c: Int) = SourcePosition.from(l, c).toOption.get
    assert(pos(1, 2).atOrAfter(1, 2), "equal counts")
    assert(pos(1, 3).atOrAfter(1, 2), "same line, earlier target char")
    assert(pos(2, 0).atOrAfter(1, 9), "later line")
    assert(!pos(1, 2).atOrAfter(1, 3), "same line, later target char")
    assert(!pos(1, 2).atOrAfter(2, 0), "earlier line")

  test("SourcePosition.from rejects a negative line or character"):
    assertLeft(SourcePosition.from(-1, 0), "line")
    assertLeft(SourcePosition.from(0, -1), "character")

  test("SourceRange.from requires end strictly after start; contains/startsAtOrAfterEnd"):
    val r = SourceRange.from(1, 0, 2, 5).toOption.get
    assertEquals((r.startLine, r.startCharacter, r.endLine, r.endCharacter), (1, 0, 2, 5))
    assert(r.contains(1, 0, 2, 5), "the full span is contained")
    assert(r.contains(1, 2, 2, 1), "an inner span is contained")
    assert(!r.contains(0, 0, 2, 5), "a span starting before is not contained")
    assert(!r.contains(1, 0, 3, 0), "a span ending after is not contained")
    assert(r.startsAtOrAfterEnd(2, 5), "the end position is at-or-after the end")
    assert(!r.startsAtOrAfterEnd(1, 4), "a mid position is before the end")
    assertLeft(SourceRange.from(2, 0, 1, 0), "after")
    assertLeft(SourceRange.from(1, 5, 1, 5), "after") // empty range rejected

  test("ScalaIdentifier.from: plain/symbolic/backticked ok; keyword/garbage/blank rejected"):
    assert(ScalaIdentifier.from("foo").isRight)
    assert(ScalaIdentifier.from("foo_$1").isRight)
    assert(ScalaIdentifier.from("+:").isRight)
    assert(ScalaIdentifier.from("`weird name`").isRight)
    assertLeft(ScalaIdentifier.from("class"), "invalid Scala identifier")
    assertLeft(ScalaIdentifier.from("1abc"), "invalid Scala identifier")
    assertLeft(ScalaIdentifier.from("   "), "non-empty")
    assertLeft(ScalaIdentifier.from("   "), "Scala identifier") // the field name is in the message

  test("StructureDimension.from maps every keyword and defaults blank to Combined"):
    import StructureDimension.*
    assertEquals(StructureDimension.from(""), Right(Combined))
    assertEquals(StructureDimension.from("combined"), Right(Combined))
    assertEquals(StructureDimension.from("extends"), Right(Extends))
    assertEquals(StructureDimension.from("memberType"), Right(MemberType))
    assertEquals(StructureDimension.from("call"), Right(Call))
    assertEquals(StructureDimension.from("implicit"), Right(Implicit))
    assertLeft(StructureDimension.from("nope"), "invalid structure dimension")

  test("StructureSort.from maps every keyword and defaults blank to Afferent"):
    import StructureSort.*
    assertEquals(StructureSort.from(""), Right(Afferent))
    assertEquals(StructureSort.from("afferent"), Right(Afferent))
    assertEquals(StructureSort.from("efferent"), Right(Efferent))
    assertEquals(StructureSort.from("instability"), Right(Instability))
    assertEquals(StructureSort.from("layer"), Right(Layer))
    assertEquals(StructureSort.from("centrality"), Right(Centrality))
    assertEquals(StructureSort.from("sccSize"), Right(SccSize))
    assertLeft(StructureSort.from("nope"), "invalid structure sort")

  test("SourceFormat.from recognises compilable/plain and defaults to Annotated"):
    import SourceFormat.*
    assertEquals(SourceFormat.from("compilable"), Compilable)
    assertEquals(SourceFormat.from("plain"), Plain)
    assertEquals(SourceFormat.from("annotated"), Annotated)
    assertEquals(SourceFormat.from("anything else"), Annotated)

  // ============================ property-based invariants ======================

  private val coord: Gen[Int] = Gen.choose(0, 6) // small range so pairs collide on the boundaries
  private def posOf(l: Int, c: Int) = SourcePosition.from(l, c).toOption.get

  property("SourcePosition.before is exactly strict lexicographic order on (line, character)"):
    forAll(coord, coord, coord, coord) { (l1, c1, l2, c2) =>
      val expected = l1 < l2 || (l1 == l2 && c1 < c2)
      posOf(l1, c1).before(posOf(l2, c2)) == expected
    }

  property(
    "atOrBefore and atOrAfter are the non-strict orders, mutually consistent at the boundary"
  ):
    forAll(coord, coord, coord, coord) { (l1, c1, l2, c2) =>
      val p = posOf(l1, c1)
      val atOrBefore = l1 < l2 || (l1 == l2 && c1 <= c2)
      val atOrAfter = l1 > l2 || (l1 == l2 && c1 >= c2)
      p.atOrBefore(l2, c2) == atOrBefore && p.atOrAfter(l2, c2) == atOrAfter
    }

  property("PackageSymbol normalization is slash-terminated (or empty) and idempotent"):
    forAll(Gen.listOf(Gen.alphaLowerChar).map(_.mkString)) { name =>
      val seg = if name.isEmpty then "" else s"pkg/$name"
      PackageSymbol.from(seg).toOption.map(_.value) match
        case Some("")  => seg.isEmpty
        case Some(out) =>
          out.endsWith("/") && PackageSymbol.from(out).toOption.map(_.value).contains(out)
        case None => false
    }
