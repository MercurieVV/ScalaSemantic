package com.github.mercurievv.scalasemantic.analysis

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class RangeSelectorPropertySuite extends munit.ScalaCheckSuite:

  private val targetGen: Gen[String] =
    Gen.oneOf(
      Gen.const("Foo.scala"),
      Gen.const("com/mercurievv/Olo.scala"),
      Gen.const("scala.String"),
      Gen.const("com.example.Bar")
    )

  private val rangeGen: Gen[(Int, Int)] =
    for
      start <- Gen.choose(1, 500)
      len <- Gen.choose(0, 50)
    yield (start, start + len)

  private val entryGen: Gen[String] =
    for
      target <- targetGen
      ranges <- Gen.nonEmptyListOf(rangeGen)
    yield s"$target[${ranges.map((s, e) => s"$s-$e").mkString(";")}]"

  property(
    "any query built from the grammar round-trips through parse as Right with the same shape"
  ):
    forAll(Gen.nonEmptyListOf(entryGen)) { entries =>
      val query = entries.mkString(";")
      RangeSelector.parse(query) match
        case Left(err)     => fail(s"expected Right for '$query', got Left($err)")
        case Right(parsed) => assertEquals(parsed.size, entries.size)
    }
