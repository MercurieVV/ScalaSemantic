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

  // MethodSymbol.from / TypeSymbol.from accept-vs-reject logic is covered generatively below
  // ("... accepts exactly a global .../type descriptor, for any name"); kept here only for the
  // error-message text, which the properties don't assert.
  test("MethodSymbol.from and TypeSymbol.from report the expected-kind in their error message"):
    assertLeft(MethodSymbol.from("com/example/Foo#"), "expected method symbol")
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

  // The relative/dot-dot accept-vs-reject logic is covered generatively below ("DocumentUri.from
  // accepts any relative path ..." / "... rejects a '..' segment at any position"); kept here for
  // the blank/absolute cases and the error-message text the properties don't assert.
  test("DocumentUri.from rejects blank and absolute paths, with the expected messages"):
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

  // contains/startsAtOrAfterEnd accept-vs-reject logic is covered generatively below
  // ("SourceRange.contains is exactly ..." / "SourceRange.startsAtOrAfterEnd is exactly ...");
  // kept here for the field accessors and the rejection error message.
  test("SourceRange.from exposes start/end fields and rejects a non-strictly-after end"):
    val r = SourceRange.from(1, 0, 2, 5).toOption.get
    assertEquals((r.startLine, r.startCharacter, r.endLine, r.endCharacter), (1, 0, 2, 5))
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

  // PackageSymbol normalization/idempotency is covered generatively below, over arbitrary segment
  // depth ("PackageSymbol.from accepts any number of segments ...").

  property("NonNegativeInt.from accepts exactly n >= 0"):
    forAll(Gen.choose(Int.MinValue, Int.MaxValue)) { n =>
      NonNegativeInt.from(n, "x").isRight == (n >= 0)
    }

  property("PositiveInt.from accepts exactly n > 0"):
    forAll(Gen.choose(Int.MinValue, Int.MaxValue)) { n =>
      PositiveInt.from(n, "x").isRight == (n > 0)
    }

  property("SourceRange.from is Right iff end is strictly after start (with valid coords)"):
    forAll(coord, coord, coord, coord) { (sl, sc, el, ec) =>
      val result = SourceRange.from(sl, sc, el, ec)
      val strictlyAfter = el > sl || (el == sl && ec > sc)
      result.isRight == strictlyAfter
    }

  property("SourcePosition.from is Right iff both line and character are >= 0"):
    forAll(Gen.choose(-5, 5), Gen.choose(-5, 5)) { (l, c) =>
      SourcePosition.from(l, c).isRight == (l >= 0 && c >= 0)
    }

  // --- SourceRange.contains / startsAtOrAfterEnd, generalised over any well-formed range --------

  /** (startLine, startChar, endLine, endChar) with end strictly after start, matching what
    * SourceRange.from itself requires.
    */
  private val genStrictQuad: Gen[(Int, Int, Int, Int)] =
    for
      sl <- coord
      sc <- coord
      el <- Gen.choose(sl, 6)
      ec <- if el == sl then Gen.choose(sc + 1, 7) else coord
    yield (sl, sc, el, ec)

  /** A quad satisfying `contains`'s own precondition (end at-or-after start), used as the queried
    * sub-span.
    */
  private val genOrderedQuad: Gen[(Int, Int, Int, Int)] =
    for
      sl <- coord
      sc <- coord
      el <- Gen.choose(sl, 6)
      ec <- if el == sl then Gen.choose(sc, 7) else coord
    yield (sl, sc, el, ec)

  property("SourceRange.contains is exactly atOrBefore(start) && atOrAfter(end)"):
    forAll(genStrictQuad, genOrderedQuad) { case ((sl, sc, el, ec), (ql, qc, ql2, qc2)) =>
      val range = SourceRange.from(sl, sc, el, ec).toOption.get
      val expected =
        (sl < ql || (sl == ql && sc <= qc)) && (el > ql2 || (el == ql2 && ec >= qc2))
      range.contains(ql, qc, ql2, qc2) == expected
    }

  property("SourceRange.startsAtOrAfterEnd is exactly end.atOrBefore(that point)"):
    forAll(genStrictQuad, coord, coord) { case ((sl, sc, el, ec), l, c) =>
      val range = SourceRange.from(sl, sc, el, ec).toOption.get
      range.startsAtOrAfterEnd(l, c) == (el < l || (el == l && ec <= c))
    }

  // --- MethodSymbol / TypeSymbol, generalised over arbitrary names ------------------------------

  property(
    "MethodSymbol.from accepts exactly a global method descriptor, for any name"
  ):
    forAll(Gen.alphaLowerStr.suchThat(_.nonEmpty)) { name =>
      MethodSymbol.from(s"com/example/Foo#$name().").isRight &&
      MethodSymbol.from(s"com/example/$name#").isLeft &&
      MethodSymbol.from(s"com/example/$name.").isLeft &&
      MethodSymbol.from(s"local$name").isLeft
    }

  property(
    "TypeSymbol.from accepts exactly a global type descriptor, for any name"
  ):
    forAll(Gen.alphaLowerStr.suchThat(_.nonEmpty)) { name =>
      TypeSymbol.from(s"com/example/$name#").isRight &&
      TypeSymbol.from(s"com/example/Foo#$name().").isLeft &&
      TypeSymbol.from(s"com/example/$name.").isLeft &&
      TypeSymbol.from(s"local$name").isLeft
    }

  // --- DocumentUri, generalised over path depth and '..' position -------------------------------

  private val genPathSegment: Gen[String] = Gen.alphaNumStr.suchThat(_.nonEmpty)
  private val genPathSegments: Gen[List[String]] =
    Gen.choose(1, 4).flatMap(n => Gen.listOfN(n, genPathSegment))

  property("DocumentUri.from accepts any relative path built from plain alphanumeric segments"):
    forAll(genPathSegments) { segs =>
      DocumentUri.from(segs.mkString("/")).isRight
    }

  property("DocumentUri.from rejects a '..' segment at any position in the path"):
    forAll(genPathSegments, Gen.choose(0, 4)) { (segs, at) =>
      val i = at % (segs.length + 1)
      val withDotDot = (segs.take(i) :+ "..") ++ segs.drop(i)
      DocumentUri.from(withDotDot.mkString("/")).isLeft
    }

  // --- PackageSymbol, generalised to arbitrary segment depth (not just zero/one) ----------------

  property(
    "PackageSymbol.from accepts any number of segments, normalizing to a trailing slash, idempotently"
  ):
    forAll(Gen.choose(0, 5).flatMap(n => Gen.listOfN(n, Gen.alphaLowerStr.suchThat(_.nonEmpty)))) {
      segs =>
        val path = segs.mkString("/")
        val expected = if segs.isEmpty then "" else s"$path/"
        PackageSymbol.from(path).toOption.map(_.value) match
          case Some(out) =>
            out == expected && PackageSymbol.from(out).toOption.map(_.value).contains(out)
          case None => false
    }
