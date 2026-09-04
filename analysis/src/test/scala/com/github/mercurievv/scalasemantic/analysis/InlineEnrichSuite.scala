package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.SourceAnnotation

class InlineEnrichSuite extends munit.FunSuite:

  test("splices an inferred-type note right after the declared name"):
    val line = "val nums = List(1, 2, 3)"
    val anns = List(SourceAnnotation(0, 4, 8, "inferred-type", ": List[Int]"))
    assertEquals(InlineEnrich.spliceLine(line, anns), "val nums: List[Int] = List(1, 2, 3)")

  test("splices an inferred-type note given in Analyzer's 'type: T' display form"):
    val line = "val nums = List(1, 2, 3)"
    val anns = List(SourceAnnotation(0, 4, 8, "inferred-type", "type: List[Int]"))
    assertEquals(InlineEnrich.spliceLine(line, anns), "val nums: List[Int] = List(1, 2, 3)")

  test("splices inferred-type-args right after the anchor, before the call's own parens"):
    val line = "val pi = render(3.14)"
    val anns = List(SourceAnnotation(0, 9, 15, "inferred-type-args", "render[Double]"))
    assertEquals(InlineEnrich.spliceLine(line, anns), "val pi = render[Double](3.14)")

  test("splices a nested-bracket type-args group whole"):
    val line = "val out = render(nums)"
    val anns = List(SourceAnnotation(0, 10, 16, "inferred-type-args", "render[List[Int]]"))
    assertEquals(InlineEnrich.spliceLine(line, anns), "val out = render[List[Int]](nums)")

  test("splices multiple notes on one line right-to-left without shifting earlier positions"):
    val line = "val labeled = nums.map(n => n -> render(n))"
    val anns = List(
      SourceAnnotation(0, 4, 11, "inferred-type", ": List[(Int, String)]"),
      SourceAnnotation(0, 33, 39, "inferred-type-args", "render[Int]")
    )
    assertEquals(
      InlineEnrich.spliceLine(line, anns),
      "val labeled: List[(Int, String)] = nums.map(n => n -> render[Int](n))"
    )

  test("appends a non-spliceable (implicit) note as a trailing bare arrow, not a comment"):
    val line = "val out = render(nums)"
    val anns = List(SourceAnnotation(0, 10, 23, "implicit", "(using listShow(using intShow))"))
    assertEquals(
      InlineEnrich.spliceLine(line, anns),
      "val out = render(nums) ⟹ (using listShow(using intShow))"
    )

  test("mixes a spliced type with a trailing implicit note on the same line"):
    val line = "val out = render(nums)"
    val anns = List(
      SourceAnnotation(0, 4, 7, "inferred-type", ": String"),
      SourceAnnotation(0, 10, 23, "implicit", "(using listShow(using intShow))")
    )
    assertEquals(
      InlineEnrich.spliceLine(line, anns),
      "val out: String = render(nums) ⟹ (using listShow(using intShow))"
    )

  test("a line with no annotations is returned unchanged"):
    val line = "val nums = List(1, 2, 3)"
    assertEquals(InlineEnrich.spliceLine(line, Nil), line)

  test("an inferred-type note at an out-of-bounds character is dropped, not spliced garbage"):
    val line = "val x = 1"
    val anns = List(SourceAnnotation(0, 100, 105, "inferred-type", ": Int"))
    assertEquals(InlineEnrich.spliceLine(line, anns), line)

  test("a 'full' note REPLACES exactly its [character, endCharacter) span, not to end of line"):
    val line = """val ranked = List("b" -> 2, "a" -> 1).sortBy(_._1)"""
    val elaborated =
      """List.apply[Tuple2[String, Int]]("b" ->[Int] 2, "a" ->[Int][String] 1).sortBy(_._1)""" +
        "(using Ordering[String])"
    val anns = List(SourceAnnotation(0, 13, 50, "full", elaborated))
    assertEquals(InlineEnrich.spliceLine(line, anns), s"val ranked = $elaborated")

  test("a 'full' note combines with a preceding inferred-type splice"):
    val line = """val ranked = List("b" -> 2, "a" -> 1).sortBy(_._1)"""
    val elaborated =
      """List.apply[Tuple2[String, Int]]("b" ->[Int] 2, "a" ->[Int][String] 1).sortBy(_._1)""" +
        "(using Ordering[String])"
    val anns = List(
      SourceAnnotation(0, 4, 10, "inferred-type", "type: List[Tuple2[String, Int]]"),
      SourceAnnotation(0, 13, 50, "full", elaborated)
    )
    assertEquals(
      InlineEnrich.spliceLine(line, anns),
      s"val ranked: List[Tuple2[String, Int]] = $elaborated"
    )

  test("an 'elaborated' note strips its 'elaborated: ' display prefix before replacing"):
    val line = "val pi = render(3.14)"
    val anns = List(
      SourceAnnotation(
        0,
        9,
        22,
        "elaborated",
        "elaborated: render[Double](3.14)(using doubleShow)"
      )
    )
    assertEquals(
      InlineEnrich.spliceLine(line, anns),
      "val pi = render[Double](3.14)(using doubleShow)"
    )

  test("two independent 'full' call sites on one line each replace only their own span"):
    val line = "val labeled = nums.map(n => n -> render(n))"
    // "n" (the `->` receiver) is at [28, 29); "render(n)" is at [33, 42) — both confirmed by
    // counting `line`'s characters directly, matching how SemanticDB ranges are 0-based/exclusive.
    val anns = List(
      SourceAnnotation(0, 4, 11, "inferred-type", "type: List[Tuple2[Int, String]]"),
      SourceAnnotation(0, 28, 29, "full", "ArrowAssoc[Int](n)"),
      SourceAnnotation(0, 33, 42, "full", "render[Int](n)(using intShow)")
    )
    assertEquals(
      InlineEnrich.spliceLine(line, anns),
      "val labeled: List[Tuple2[Int, String]] = " +
        "nums.map(n => ArrowAssoc[Int](n) -> render[Int](n)(using intShow))"
    )
