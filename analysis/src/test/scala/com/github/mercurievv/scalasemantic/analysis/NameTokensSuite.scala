package com.github.mercurievv.scalasemantic.analysis

class NameTokensSuite extends munit.FunSuite:

  private val src =
    """package demo
      |
      |import cats.effect.IO
      |
      |object Holder:
      |  val label: String = "x"
      |  def run(n: Int): IO[Unit] = IO.pure(()).void
      |  def broken: Int = undefinedName + 1
      |""".stripMargin

  private def namesIn(ranges: (Int, Int)*): List[String] =
    NameTokens.in(src, ranges).getOrElse(fail("did not parse")).map(_.name)

  test("an empty range list yields every name in the file") {
    val all = namesIn()
    assert(all.contains("IO"), all.toString)
    assert(all.contains("label"), all.toString)
    assert(all.contains("undefinedName"), all.toString)
  }

  test("a range selects only the names whose position is inside it") {
    // line 7 (0-based) is `def broken: Int = undefinedName + 1`
    val names = namesIn((7, 8))
    assert(names.contains("undefinedName"), names.toString)
    assert(names.contains("broken"), names.toString)
    assert(!names.contains("label"), names.toString)
    assert(!names.contains("IO"), names.toString)
  }

  test("kinds: type, term, method") {
    val toks = NameTokens.in(src, Seq((6, 7))).getOrElse(fail("did not parse"))
    val byName = toks.map(t => t.name -> t.kind).toMap
    assertEquals(byName.get("Int"), Some("type"))
    assertEquals(byName.get("IO"), Some("type")) // in `IO[Unit]`, a Type.Name
    assertEquals(byName.get("pure"), Some("method")) // the `fun` of an Apply
    assertEquals(byName.get("n"), Some("term"))
  }

  test("roles: a definition's own name vs a use") {
    val toks = NameTokens.in(src, Seq((5, 6))).getOrElse(fail("did not parse"))
    assertEquals(toks.find(_.name == "label").map(_.role), Some("definition"))
    assertEquals(toks.find(_.name == "String").map(_.role), Some("use"))
  }

  test("positions are 0-based and point at the name's first character") {
    val toks = NameTokens.in(src, Seq((7, 8))).getOrElse(fail("did not parse"))
    val undefined = toks.find(_.name == "undefinedName").getOrElse(fail("not found"))
    assertEquals(undefined.line, 7)
    assertEquals(
      src.linesIterator.toList.lift(7).map(_.indexOf("undefinedName")),
      Some(undefined.character)
    )
  }

  test("a file that does not parse yields None, not an empty list") {
    assertEquals(NameTokens.in("object Broken:\n  def f(: Int = 1\n", Nil), None)
  }

  test("import selectors are names too (an invented import is a hallucination)") {
    val names = namesIn((2, 3))
    assert(names.contains("IO"), names.toString)
    assert(names.contains("cats"), names.toString)
  }
