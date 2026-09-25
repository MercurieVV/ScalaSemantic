package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.{Enclosing, Literal}

class LiteralTokensSuite extends munit.FunSuite:

  private def lits(src: String, ranges: Seq[(Int, Int)] = Nil): List[Literal] =
    LiteralTokens.in(src, ranges).getOrElse(fail("did not parse"))

  private def kv(src: String): List[(String, String)] = lits(src).map(l => l.kind -> l.value)

  test("each kind is reported with a normalised value") {
    val src =
      """package p
        |object O:
        |  val a = 0x10
        |  val b = 1_000
        |  val c = 7L
        |  val d = 2.5
        |  val e = 1.5f
        |  val f = 'x'
        |  val g = "tab\there"
        |""".stripMargin
    assertEquals(
      kv(src),
      List(
        "int" -> "16",
        "int" -> "1000",
        "long" -> "7",
        "double" -> "2.5",
        "float" -> "1.5",
        "char" -> "x",
        "string" -> "tab\there"
      )
    )
  }

  test("booleans, null and unit are not reported") {
    val src =
      """package p
        |object O:
        |  val a = true
        |  val b = null
        |  val c = ()
        |  val d = 3
        |""".stripMargin
    assertEquals(kv(src), List("int" -> "3"))
  }

  test("an interpolation contributes its literal parts") {
    val src =
      """package p
        |object O:
        |  def f(n: Int) = s"note $n end"
        |""".stripMargin
    assertEquals(kv(src), List("string" -> "note ", "string" -> " end"))
  }

  test("a negative literal is one row with a negative value") {
    val src =
      """package p
        |object O:
        |  val a = -5
        |  val b = -2.5
        |""".stripMargin
    assertEquals(kv(src), List("int" -> "-5", "double" -> "-2.5"))
  }

  test("a unary minus over a parenthesised literal is one row with a negative value") {
    // scalameta folds a plain `-5` into `Lit.Int(-5)` (measured), so the unary-minus branch is
    // reached only when the minus applies to a literal it did not fold -- a parenthesised one.
    val src =
      """package p
        |object O:
        |  val a = -(7)
        |""".stripMargin
    assertEquals(kv(src), List("int" -> "-7"))
  }

  test("a literal pattern in a match case is reported") {
    val src =
      """package p
        |object O:
        |  def f(x: Int): Int = x match
        |    case 3 => 42
        |    case _ => 0
        |""".stripMargin
    assertEquals(kv(src), List("int" -> "3", "int" -> "42", "int" -> "0"))
  }

  test("enclosing is the nearest definition: a val table, a match in a def, a nested object") {
    val src =
      """package p
        |object Outer:
        |  val table = Map("C4" -> 60)
        |  def f(x: Int): Int = x match
        |    case 7 => 1
        |  object Inner:
        |    val z = 99
        |""".stripMargin
    val byValue = lits(src).map(l => l.value -> l.enclosing).toMap
    assertEquals(byValue("C4"), Some(Enclosing("val", "table", 2)))
    assertEquals(byValue("60"), Some(Enclosing("val", "table", 2)))
    assertEquals(byValue("7"), Some(Enclosing("def", "f", 3)))
    assertEquals(byValue("99"), Some(Enclosing("val", "z", 6)))
  }

  test("enclosing covers class bodies and is absent outside every definition") {
    // A top-level type alias is not one of the enclosing kinds, so its literal type has no
    // enclosing definition; a statement in a class body is enclosed by the class.
    val src =
      """package p
        |type Three = 3
        |class C:
        |  println(5)
        |""".stripMargin
    val all = lits(src)
    val five = all.find(_.value == "5").getOrElse(fail(all.toString))
    assertEquals(five.enclosing, Some(Enclosing("class", "C", 2)))
    val three = all.find(_.value == "3").getOrElse(fail(all.toString))
    assertEquals(three.enclosing, None)
  }

  test("ranges select by start line; absent ranges mean the whole file") {
    val src =
      """package p
        |object O:
        |  val a = 11
        |  val b = 22
        |  val c = 33
        |""".stripMargin
    assertEquals(lits(src).map(_.value), List("11", "22", "33"))
    assertEquals(lits(src, Seq((3, 4))).map(_.value), List("22"))
  }

  test("CRLF line endings keep line numbers") {
    val src = "package p\r\nobject O:\r\n  val a = 11\r\n  val b = 22\r\n"
    assertEquals(lits(src).map(l => l.line -> l.value), List(2 -> "11", 3 -> "22"))
  }

  test("a file that does not parse yields None, not an empty list") {
    assertEquals(LiteralTokens.in("object O { val = }", Nil), None)
  }

  test("forFile turns a parse failure into an error") {
    val r = LiteralTokens.forFile("A.scala", "object O { val = }", Nil)
    assertEquals(r.literals, Nil)
    assert(r.error.exists(_.nonEmpty), r.toString)
  }

  test("forFile bounds the answer at 2,000 literals and counts the rest") {
    val body = (1 to 2500).map(i => s"  val v$i = ${i + 100}").mkString("\n")
    val src = s"package p\nobject O:\n$body\n"
    val r = LiteralTokens.forFile("A.scala", src, Nil)
    assertEquals(r.literals.size, LiteralTokens.MaxPerFile)
    assertEquals(r.omitted, 500)
    assertEquals(r.literals.head.value, "101")
    assertEquals(r.error, None)
  }
