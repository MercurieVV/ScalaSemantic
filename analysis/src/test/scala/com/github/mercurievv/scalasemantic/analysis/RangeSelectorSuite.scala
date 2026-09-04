package com.github.mercurievv.scalasemantic.analysis

class RangeSelectorSuite extends munit.FunSuite:

  test("parses a single target with a single range"):
    assertEquals(
      RangeSelector.parse("Foo.scala[10-20]"),
      Right(List(RangeSelector.Entry("Foo.scala", List(RangeSelector.LineRange(10, 20)))))
    )

  test("parses a single target with multiple ranges"):
    assertEquals(
      RangeSelector.parse("scala.String[10-20;30-40]"),
      Right(
        List(
          RangeSelector.Entry(
            "scala.String",
            List(RangeSelector.LineRange(10, 20), RangeSelector.LineRange(30, 40))
          )
        )
      )
    )

  test("parses multiple targets separated by ';' outside brackets"):
    assertEquals(
      RangeSelector.parse("scala.String[10-20;30-40];com/mercurievv/Olo.scala[50-55]"),
      Right(
        List(
          RangeSelector.Entry(
            "scala.String",
            List(RangeSelector.LineRange(10, 20), RangeSelector.LineRange(30, 40))
          ),
          RangeSelector.Entry("com/mercurievv/Olo.scala", List(RangeSelector.LineRange(50, 55)))
        )
      )
    )

  test("rejects empty input"):
    assert(RangeSelector.parse("").isLeft)
    assert(RangeSelector.parse("   ").isLeft)

  test("rejects a range with end before start"):
    assert(RangeSelector.parse("Foo.scala[20-10]").isLeft)

  test("rejects malformed range syntax"):
    assert(RangeSelector.parse("Foo.scala[abc]").isLeft)

  test("rejects text outside any target[...] entry"):
    assert(RangeSelector.parse("Foo.scala[10-20] garbage").isLeft)

  test("rejects an entry with an empty target"):
    assert(RangeSelector.parse("[10-20]").isLeft)

  test("looksLikeFileTarget: true for a path with a slash"):
    assert(RangeSelector.looksLikeFileTarget("com/mercurievv/Olo.scala"))

  test("looksLikeFileTarget: true for a bare .scala filename"):
    assert(RangeSelector.looksLikeFileTarget("Foo.scala"))

  test("looksLikeFileTarget: false for a dotted FQN"):
    assert(!RangeSelector.looksLikeFileTarget("scala.String"))
