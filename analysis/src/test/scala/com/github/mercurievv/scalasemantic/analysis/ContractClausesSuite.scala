package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.Contract

class ContractClausesSuite extends munit.FunSuite:

  private def defs(src: String): List[Contract] =
    ContractClauses.in(src).getOrElse(fail("did not parse"))

  private def one(src: String): Contract =
    defs(src) match
      case List(c) => c
      case other   => fail(s"expected one contracted def, got $other")

  test("require, infix ensuring and decreases are extracted with their text") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    require(x >= 0)
        |    decreases(x)
        |    x + 1
        |  } ensuring (r => r > x)
        |""".stripMargin
    val c = one(src)
    assertEquals(c.key, "K.f")
    assertEquals(c.line, 2)
    assertEquals(c.requires.map(_.text), List("require(x >= 0)"))
    assertEquals(c.decreases.map(_.text), Some("decreases(x)"))
    assertEquals(c.ensuring.map(_.text), Some("r => r > x"))
  }

  test("several requires are kept in source order") {
    val src =
      """package p
        |object K:
        |  def f(a: Int, b: Int): Int = {
        |    require(a > 0)
        |    require(b > 0)
        |    a + b
        |  }
        |""".stripMargin
    assertEquals(one(src).requires.map(_.text), List("require(a > 0)", "require(b > 0)"))
  }

  test("method-call ensuring has the same text and shape as infix ensuring") {
    val infix =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    x
        |  } ensuring (r => r == x)
        |""".stripMargin
    val call =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    x
        |  }.ensuring(r => r == x)
        |""".stripMargin
    assertEquals(one(infix).ensuring.map(_.text), one(call).ensuring.map(_.text))
    assertEquals(one(call).ensuring.map(_.text), Some("r => r == x"))
    assertEquals(one(infix).ensuring.map(_.shape), one(call).ensuring.map(_.shape))
  }

  test("a block-argument ensuring is extracted") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    x
        |  } ensuring { r => r == x }
        |""".stripMargin
    assertEquals(one(src).ensuring.map(_.text), Some("{ r => r == x }"))
  }

  test("formatting does not change shape") {
    val compact =
      """package p
        |object K:
        |  def f(x: Int): Int = { require(x>=0); x } ensuring (r => r>=0)
        |""".stripMargin
    val spread =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    require(  x >= 0  )
        |    x
        |  } ensuring (r =>
        |    r >= 0)
        |""".stripMargin
    val a = one(compact)
    val b = one(spread)
    assertEquals(a.requires.map(_.shape), b.requires.map(_.shape))
    assertEquals(a.ensuring.map(_.shape), b.ensuring.map(_.shape))
  }

  test("body-only edit leaves ensuring text and shape unchanged") {
    val originalBody =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    x + 1
        |  } ensuring (r => r > x)
        |""".stripMargin
    val changedBody =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    x + 2
        |  } ensuring (r => r > x)
        |""".stripMargin
    val orig = one(originalBody)
    val changed = one(changedBody)
    assertEquals(orig.ensuring.map(_.text), changed.ensuring.map(_.text))
    assertEquals(orig.ensuring.map(_.shape), changed.ensuring.map(_.shape))
  }

  test("a semantic change to a clause changes its shape") {
    def src(bound: String) =
      s"""package p
         |object K:
         |  def f(x: Int): Int = {
         |    require(x >= $bound)
         |    x
         |  }
         |""".stripMargin
    assertNotEquals(one(src("0")).requires.map(_.shape), one(src("1")).requires.map(_.shape))
  }

  test("a def without clauses is not reported") {
    val src =
      """package p
        |object K:
        |  def plain(x: Int): Int = x + 1
        |  def checked(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    assertEquals(defs(src).map(_.key), List("K.checked"))
  }

  test("a require nested inside an expression is not a contract clause") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    val y = if x > 0 then { require(x < 10); x } else 0
        |    y
        |  }
        |""".stripMargin
    assertEquals(defs(src), Nil)
  }

  test("nested owners form the key") {
    val src =
      """package p
        |object Outer:
        |  class Inner:
        |    def g(x: Int): Int = { require(x > 0); x }
        |  def f(x: Int): Int =
        |    def local(y: Int): Int = { require(y > 0); y }
        |    local(x)
        |""".stripMargin
    assertEquals(defs(src).map(_.key).sorted, List("Outer.Inner.g", "Outer.f.local"))
  }

  test("overloads get parameter names") {
    val src =
      """package p
        |object K:
        |  def f(a: Int): Int = { require(a > 0); a }
        |  def f(a: Int, b: Int): Int = { require(a > b); a }
        |  def g(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    assertEquals(defs(src).map(_.key), List("K.f(a: Int)", "K.f(a: Int, b: Int)", "K.g"))
  }

  test("a top-level def has no owner prefix") {
    val src =
      """package p
        |def f(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    assertEquals(one(src).key, "f")
  }

  test("shape is 16 lowercase hex characters") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    assert(one(src).requires.forall(_.shape.matches("[0-9a-f]{16}")), one(src).toString)
  }

  test("text that does not parse is None, and forFile names it") {
    assertEquals(ContractClauses.in("object O { def = }"), None)
    val f = ContractClauses.forFile("A.scala", "object O { def = }")
    assertEquals(f.defs, Nil)
    assert(f.error.exists(_.contains("does not parse")), f.toString)
  }

  test("trailing comments do not change shape") {
    val without =
      """package p
        |object K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    val withComment =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    require(x > 0) // why?
        |    x
        |  }
        |""".stripMargin
    assertEquals(one(without).requires.map(_.shape), one(withComment).requires.map(_.shape))
  }

  test("leading comments do not change shape") {
    val without =
      """package p
        |object K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    val withComment =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    // precondition
        |    require(x > 0)
        |    x
        |  }
        |""".stripMargin
    assertEquals(one(without).requires.map(_.shape), one(withComment).requires.map(_.shape))
  }

  test("inner comments in postcondition do not change shape") {
    val without =
      """package p
        |object K:
        |  def f(x: Int): Int = { x } ensuring (r => r > 0)
        |""".stripMargin
    val withComment =
      """package p
        |object K:
        |  def f(x: Int): Int = { x } ensuring (r => // check
        |    r > 0)
        |""".stripMargin
    assertEquals(one(without).ensuring.map(_.shape), one(withComment).ensuring.map(_.shape))
  }

  test("braces-wrapped require has same shape as parenthesized") {
    val parens =
      """package p
        |object K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    val braces =
      """package p
        |object K:
        |  def f(x: Int): Int = { require { x > 0 }; x }
        |""".stripMargin
    assertEquals(one(parens).requires.map(_.shape), one(braces).requires.map(_.shape))
  }

  test("braces-wrapped ensuring has same shape as parenthesized") {
    val parens =
      """package p
        |object K:
        |  def f(x: Int): Int = { x } ensuring (r => r > 0)
        |""".stripMargin
    val braces =
      """package p
        |object K:
        |  def f(x: Int): Int = { x } ensuring { r => r > 0 }
        |""".stripMargin
    assertEquals(one(parens).ensuring.map(_.shape), one(braces).ensuring.map(_.shape))
  }

  test("qualified require is reported") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = { scala.Predef.require(x > 0); x }
        |""".stripMargin
    assertEquals(one(src).requires.size, 1)
    assert(one(src).requires(0).text.contains("require"), one(src).toString)
  }

  test("qualified decreases is reported") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    stainless.lang.decreases(x)
        |    x
        |  }
        |""".stripMargin
    assert(one(src).decreases.isDefined)
    assert(one(src).decreases.get.text.contains("decreases"), one(src).toString)
  }

  test("ensuring with type argument is reported") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = { x } ensuring[Int](r => r > 0)
        |""".stripMargin
    assertEquals(one(src).ensuring.map(_.text), Some("r => r > 0"))
  }

  test("require after other statements is not a contract clause") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    val y = x
        |    require(x > 0)
        |    x
        |  }
        |""".stripMargin
    assertEquals(defs(src), Nil)
  }

  test("multiple requires in leading run are all reported") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    require(x > 0)
        |    require(x < 100)
        |    x
        |  }
        |""".stripMargin
    assertEquals(one(src).requires.size, 2)
  }

  test("decreases and requires can be intermixed in leading run") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    require(x > 0)
        |    decreases(x)
        |    require(x < 100)
        |    x
        |  }
        |""".stripMargin
    assertEquals(one(src).requires.size, 2)
    assert(one(src).decreases.isDefined)
  }

  test("all keys are distinct in a file with companion class and object") {
    val src =
      """package p
        |class K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |object K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |""".stripMargin
    val keys = defs(src).map(_.key)
    assertEquals(keys.distinct.size, keys.size)
  }

  test("overloads with typed parameter names are distinct") {
    val src =
      """package p
        |object K:
        |  def f(a: Int): Int = { require(a > 0); a }
        |  def f(a: String): Int = { require(a.nonEmpty); a.length }
        |""".stripMargin
    val keys = defs(src).map(_.key)
    assertEquals(keys.distinct.size, keys.size)
    assertEquals(keys, List("K.f(a: Int)", "K.f(a: String)"))
  }

  test("floating-point and integer type differences change shape") {
    val float0_1 =
      """package p
        |object K:
        |  def f(x: Double): Int = { require(x == 0.1); 1 }
        |""".stripMargin
    val float0_1f =
      """package p
        |object K:
        |  def f(x: Double): Int = { require(x == 0.1f); 1 }
        |""".stripMargin
    assertNotEquals(one(float0_1).requires(0).shape, one(float0_1f).requires(0).shape)
  }

  test("long and int type differences change shape") {
    val int2147 =
      """package p
        |object K:
        |  def f(x: Long): Int = { require(x >= 2147483647); 1 }
        |""".stripMargin
    val long2147 =
      """package p
        |object K:
        |  def f(x: Long): Int = { require(x >= 2147483647L); 1 }
        |""".stripMargin
    assertNotEquals(one(int2147).requires(0).shape, one(long2147).requires(0).shape)
  }

  test("decreases before require in leading run is reported") {
    val src =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    decreases(x)
        |    require(x > 0)
        |    x
        |  }
        |""".stripMargin
    assertEquals(one(src).requires.size, 1)
    assert(one(src).decreases.isDefined)
  }

  test("three identical defs on one line in one owner get keys #1 #2 #3 by source position") {
    val d = "def f(x: Int): Int = { require(x > 0); x }"
    val src = s"package p; object O { $d; $d; $d }\n"
    assertEquals(
      defs(src).map(_.key),
      List("O.f(x: Int)#1", "O.f(x: Int)#2", "O.f(x: Int)#3")
    )
  }

  test("all six key-collision cases get distinct, explicit keys") {
    val src =
      """package p
        |class K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |object K:
        |  def f(x: Int): Int = { require(x > 0); x }
        |  given Ord[Int] with
        |    def cmp(x: Int, b: Int): Int = { require(x != b); 0 }
        |  given Ord[String] with
        |    def cmp(x: Int, b: Int): Int = { require(x != b); 0 }
        |  given gi: Ord[Int] with
        |    def cmp(x: Int, b: Int): Int = { require(x != b); 0 }
        |  given gs: Ord[String] with
        |    def cmp(x: Int, b: Int): Int = { require(x != b); 0 }
        |  given ga: Ord[Long] = new Ord[Long]:
        |    def cmp(x: Int, b: Int): Int = { require(x != b); 0 }
        |  given Ord[Boolean] = new Ord[Boolean]:
        |    def cmp(x: Int, b: Int): Int = { require(x != b); 0 }
        |  extension (a: Int) def f(x: Int): Int = { require(a > 0); x }
        |  extension (c: String) def f(x: Int): Int = { require(c.nonEmpty); x }
        |  val v = {
        |    def f(z: Int): Int = { require(z > 0); z }
        |    f(1)
        |  }
        |package a {
        |  object O { def f(x: Int): Int = { require(x > 0); x } }
        |}
        |package b {
        |  object O { def f(x: Int): Int = { require(x > 0); x } }
        |}
        |""".stripMargin
    val keys = defs(src).map(_.key)
    assertEquals(keys.distinct, keys)
    assertEquals(
      keys,
      List(
        "pkg:p.class:K.f",
        "pkg:p.K.f(x: Int)",
        "K.given[Ord[Int]].cmp",
        "K.given[Ord[String]].cmp",
        "K.gi.cmp",
        "K.gs.cmp",
        "K.ga.cmp",
        "K.given[Ord[Boolean]].cmp",
        "pkg:p.K.f(a: Int, x: Int)",
        "pkg:p.K.f(c: String, x: Int)",
        "K.v.f",
        "pkg:p.pkg:a.O.f",
        "pkg:p.pkg:b.O.f"
      )
    )
  }

  test("key segments ignore formatting inside types") {
    val src =
      """package p
        |object K:
        |  given Ord[ Int ] with
        |    def cmp(x: Int, b: Int): Int = { require(x != b); 0 }
        |  def f(a :  List[ Int ]): Int = { require(a.nonEmpty); 0 }
        |  def f(a: Int): Int = { require(a > 0); 0 }
        |""".stripMargin
    assertEquals(
      defs(src).map(_.key),
      List("K.given[Ord[Int]].cmp", "K.f(a: List[Int])", "K.f(a: Int)")
    )
  }

  test("char literals: braces do not change shape, a different char does") {
    def src(cond: String) =
      s"""package p
         |object K:
         |  def f(x: Int): Int = { require($cond); x }
         |""".stripMargin
    val braced = one(src("{'a'} == 'a'")).requires.map(_.shape)
    val plain = one(src("'a' == 'a'")).requires.map(_.shape)
    val other = one(src("'b' == 'a'")).requires.map(_.shape)
    assertEquals(braced, plain)
    assertNotEquals(plain, other)
  }

  // --- F1: `ensuring` on the body's last expression -----------------------------------------------

  test("a braceless body's trailing ensuring is reported in all three forms") {
    def src(ens: String) =
      s"""package p
         |object K:
         |  def f(x: Int): Int =
         |    require(x >= 0)
         |    $ens
         |""".stripMargin
    val forms = List(
      "(x + 1).ensuring(r => r > x)",
      "(x + 1) ensuring (r => r > x)",
      "(x + 1).ensuring[Int](r => r > x)"
    ).map(f => one(src(f)))
    forms.foreach { c =>
      assertEquals(c.requires.map(_.text), List("require(x >= 0)"))
      assertEquals(c.ensuring.map(_.text), Some("r => r > x"))
    }
    assertEquals(forms.map(_.ensuring.map(_.shape)).distinct.size, 1)
  }

  test("vals between the require and a trailing ensuring keep both clauses") {
    val src =
      """package p
        |object K:
        |  def inRange(lo: Int, hi: Int, x: Int): Boolean =
        |    require(lo <= hi)
        |    val above = x >= lo
        |    val below = x < hi
        |    (above && below).ensuring(res => res == (above && below))
        |""".stripMargin
    val c = one(src)
    assertEquals(c.requires.map(_.text), List("require(lo <= hi)"))
    assertEquals(c.ensuring.map(_.text), Some("res => res == (above && below)"))
  }

  test("an ensuring inside a braced single-statement body is reported") {
    val onlyEnsuring =
      """package p
        |object K:
        |  def f(x: Int): Int = { (x + 1).ensuring(r => r > x) }
        |""".stripMargin
    assertEquals(one(onlyEnsuring).ensuring.map(_.text), Some("r => r > x"))
    val wrapped =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    { require(x > 0); x }.ensuring(r => r > 0)
        |  }
        |""".stripMargin
    assertEquals(one(wrapped).requires.map(_.text), List("require(x > 0)"))
    assertEquals(one(wrapped).ensuring.map(_.text), Some("r => r > 0"))
    val nested =
      """package p
        |object K:
        |  def f(x: Int): Int = {
        |    require(x > 0)
        |    { { x.ensuring(r => r > 0) } }
        |  }
        |""".stripMargin
    assertEquals(one(nested).requires.map(_.text), List("require(x > 0)"))
    assertEquals(one(nested).ensuring.map(_.text), Some("r => r > 0"))
  }

  test("every def of the repo's PureKernels.scala reports its require and ensuring") {
    val path = java.nio.file.Paths.get(
      "analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/PureKernels.scala"
    )
    val f = ContractClauses.forFile("PureKernels.scala", java.nio.file.Files.readString(path))
    assertEquals(f.error, None)
    assertEquals(
      f.defs.map(_.key),
      List(
        "PureKernels.instability",
        "PureKernels.pageRankBase",
        "PureKernels.rangeContains",
        "PureKernels.rangeSpan",
        "PureKernels.nextLevel"
      )
    )
    f.defs.foreach { c =>
      assert(c.ensuring.isDefined, c.toString)
      assertEquals(c.requires.size, 1, c.toString)
    }
  }

  // --- F2: same-named owners ---------------------------------------------------------------------

  private def shapesByKey(src: String): Map[String, List[String]] =
    defs(src).map(c => c.key -> c.requires.map(_.shape)).toMap

  test("swapping contracts between a companion class and object changes the report") {
    def src(inClass: String, inObject: String) =
      s"""package p
         |class K { def f(x: Int) = { require($inClass); x } }
         |object K { def f(x: Int) = { require($inObject); x } }
         |""".stripMargin
    val orig = shapesByKey(src("x > 0", "x < 0"))
    val swapped = shapesByKey(src("x < 0", "x > 0"))
    assertNotEquals(orig, swapped)
    assertEquals(orig.keySet, Set("class:K.f", "K.f"))
  }

  test("swapping contracts between two package blocks changes the report") {
    def src(inA: String, inB: String) =
      s"""package a { object O { def f(x: Int) = { require($inA); x } } }
         |package b { object O { def f(x: Int) = { require($inB); x } } }
         |""".stripMargin
    val orig = shapesByKey(src("x > 0", "x < 0"))
    val swapped = shapesByKey(src("x < 0", "x > 0"))
    assertNotEquals(orig, swapped)
    assertEquals(orig.keySet, Set("pkg:a.O.f", "pkg:b.O.f"))
  }

  // --- F3 / F4: fingerprint soundness ------------------------------------------------------------

  test("a single-statement block holding a definition keeps its braces in the shape") {
    def src(post: String) =
      s"""package p
         |object K:
         |  val ok = false
         |  def f(x: Int): Int = { x }.ensuring(r => $post)
         |""".stripMargin
    assertNotEquals(
      one(src("{ val ok = r > 0; ok }")).ensuring.map(_.shape),
      one(src("{ { val ok = r > 0 }; ok }")).ensuring.map(_.shape)
    )
  }

  test("a string literal cannot forge the fingerprint of other syntax") {
    def src(args: String) =
      s"""package p
         |object K:
         |  def f(x: Int): Int = { require(check($args)); x }
         |""".stripMargin
    assertNotEquals(
      one(src("\"a\", \"b\"")).requires.map(_.shape),
      one(src("\"a\\\") (Lit.String \\\"b\"")).requires.map(_.shape)
    )
  }
