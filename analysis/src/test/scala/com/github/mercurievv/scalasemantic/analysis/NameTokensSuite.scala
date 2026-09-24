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
    def kindAt(name: String, character: Int): Option[String] =
      toks.find(t => t.name == name && t.character == character).map(_.kind)
    val line = src.linesIterator.toList.lift(6).getOrElse(fail("no such line"))
    val byName = toks.map(t => t.name -> t.kind).toMap
    assertEquals(byName.get("Int"), Some("type"))
    // `IO` is spelled twice on this line — the return-type annotation and the call. Pin the token by
    // its column; collapsing same-named tokens into a map would assert on whichever parsed last.
    assertEquals(kindAt("IO", line.indexOf("IO[Unit]")), Some("type"))
    assertEquals(kindAt("IO", line.indexOf("IO.pure")), Some("term"))
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

  test("a renaming import contributes the source name, never the alias"):
    val src = "import scala.collection.immutable.{Queue => Q, Seq as S}\n"
    val names = NameTokens.in(src, Nil).getOrElse(fail("did not parse")).map(_.name)
    assert(names.contains("Queue"), names.toString)
    assert(names.contains("Seq"), names.toString)
    assert(!names.contains("Q"), names.toString)
    assert(!names.contains("S"), names.toString)

  // --- constructs the compiler emits no occurrence for -----------------------
  // Each of these is ORDINARY, CORRECT Scala 3. A token the compiler never covers joins to nothing
  // and would be reported `resolved: false`, i.e. as a hallucination that blocks a turn.

  test("a `derives` clause's type name is not a token"):
    val src = "package p\ncase class C(x: Int) derives E\n"
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    assert(!toks.exists(_.name == "E"), toks.toString)
    assert(toks.exists(_.name == "C"), toks.toString)
    assert(toks.exists(_.name == "Int"), toks.toString)

  test("a qualified `derives` clause is skipped through the whole type path"):
    val src = "package p\ncase class C(x: Int) derives q.E\n"
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    assert(!toks.exists(_.name == "E"), toks.toString)
    assert(!toks.exists(_.name == "q"), toks.toString)

  test("a backticked definition's column points inside the backticks"):
    val src = "package p\nobject O:\n  val `ok`: Int = 1\n  def f: Int = `ok` + 1\n"
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    val defn = toks.find(t => t.name == "ok" && t.line == 2).getOrElse(fail(toks.toString))
    // The backtick is at column 6; the compiler's DEFINITION occurrence starts at 7.
    assertEquals(defn.character, 7)
    val use = toks.find(t => t.name == "ok" && t.line == 3).getOrElse(fail(toks.toString))
    val line3 = src.linesIterator.toList.lift(3).getOrElse(fail("no line"))
    assertEquals(use.character, line3.indexOf("`ok`") + 1)

  test("a plain definition's column is untouched"):
    val src = "package p\nobject O:\n  val ok: Int = 1\n"
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    assertEquals(toks.find(_.name == "ok").map(_.character), Some(6))

  test("a named export selector is a binding, not a reference"):
    val src = "package p\nobject O:\n  val i = 1\n  export i.v\n  export i.*\n"
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    assert(!toks.exists(t => t.name == "v"), toks.toString)
    // The export's qualifier IS a reference and stays.
    assert(toks.exists(t => t.line == 3 && t.name == "i"), toks.toString)
    assert(toks.exists(t => t.line == 4 && t.name == "i"), toks.toString)

  test("an ordinary import selector is still a reference"):
    val src = "import scala.collection.immutable.Queue\n"
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    assert(toks.exists(_.name == "Queue"), toks.toString)

  test("a macro quote's spliced name is not a token"):
    val src = "package p\nobject O:\n  inline def s(inline x: Int): String = ${ impl('x) }\n"
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    assert(!toks.exists(t => t.name == "x" && t.character > 30), toks.toString)
    // The splice's own call is an ordinary reference and stays reportable.
    assert(toks.exists(_.name == "impl"), toks.toString)

  test("an annotated ascription in a val costs its declared type and the rhs's binders"):
    val src =
      """package p
        |object O:
        |  val o: Option[Int] = None
        |  val w: Int = (o: @unchecked) match { case Some(n) => n }
        |  val v: Int = 2
        |""".stripMargin
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    val line3 = toks.filter(_.line == 3)
    // Lost to the compiler, so not reportable: the declared type, the annotation, the binder.
    assert(!line3.exists(_.name == "Int"), line3.toString)
    assert(!line3.exists(_.name == "unchecked"), line3.toString)
    assert(!line3.exists(_.name == "n"), line3.toString)
    // Everything else on the same statement is still checked.
    assert(line3.exists(_.name == "w"), line3.toString)
    assert(line3.exists(_.name == "o"), line3.toString)
    assert(line3.exists(_.name == "Some"), line3.toString)
    assert(toks.exists(t => t.line == 2 && t.name == "Option"), toks.toString)
    assert(toks.exists(t => t.line == 4 && t.name == "v"), toks.toString)

  test("an annotated ascription in a def body costs only the annotation"):
    // The signature must stay reportable: blanking it would ship a hallucinated parameter type.
    val src =
      """package p
        |object O:
        |  val o: Option[Int] = None
        |  def f(p: Bogus): Int = (o: @unchecked) match { case Some(n) => n }
        |""".stripMargin
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    val line3 = toks.filter(_.line == 3)
    assert(line3.exists(_.name == "Bogus"), line3.toString)
    assert(line3.exists(_.name == "f"), line3.toString)
    assert(line3.exists(_.name == "p"), line3.toString)
    assert(line3.exists(_.name == "Int"), line3.toString)
    assert(line3.exists(_.name == "n"), line3.toString)
    assert(!line3.exists(_.name == "unchecked"), line3.toString)

  test("a blind spot stops at the statement, not at the enclosing method"):
    val src =
      """package p
        |object O:
        |  def f(o: Option[Int], q: Bogus): Int =
        |    val a: Int = 1
        |    val w: Int = (o: @unchecked) match { case Some(n) => n }
        |    a + w
        |""".stripMargin
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    assert(toks.exists(t => t.line == 2 && t.name == "Bogus"), toks.toString)
    assert(toks.exists(t => t.line == 3 && t.name == "a"), toks.toString)
    assert(toks.exists(t => t.line == 5 && t.name == "a"), toks.toString)
    val line4 = toks.filter(_.line == 4)
    assert(!line4.exists(_.name == "Int"), line4.toString)
    assert(!line4.exists(_.name == "n"), line4.toString)
    assert(line4.exists(_.name == "w"), line4.toString)
    assert(line4.exists(_.name == "Some"), line4.toString)

  test("an unrelated name in an annotated val's rhs is still reported"):
    // The net is per-binder-name, not per-statement, so a hallucination in the rhs survives it.
    val src =
      """package p
        |object O:
        |  val o: Option[Int] = None
        |  val w: Int = (o: @unchecked) match { case Some(n) => n.bogusMember }
        |""".stripMargin
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    assert(toks.exists(_.name == "bogusMember"), toks.toString)

  test("an annotation nested one level inside the rhs (a call argument) costs nothing"):
    // Measured: the annotation is not the val's rhs itself here (the rhs is `id(...)`, a call), so
    // the compiler covers the declared type and the binder normally — only the annotation is blind.
    val src =
      """package p
        |object O:
        |  val o: Option[Int] = None
        |  def id[A](x: A): A = x
        |  val w: Int = id((o: @unchecked) match { case Some(n) => n })
        |""".stripMargin
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    val line4 = toks.filter(_.line == 4)
    assert(line4.exists(_.name == "Int"), line4.toString)
    assert(line4.exists(_.name == "n"), line4.toString)
    assert(line4.exists(_.name == "Some"), line4.toString)
    assert(!line4.exists(_.name == "unchecked"), line4.toString)

  test("two independent matches on one rhs, only one annotated: the other's binder is untouched"):
    val src =
      """package p
        |object O:
        |  val o: Option[Int] = None
        |  val p: Option[Int] = None
        |  val w: Int = ((o: @unchecked) match {case Some(n)=>n}) + (p match {case Some(m)=>m})
        |""".stripMargin
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    val line4 = toks.filter(_.line == 4)
    // Not the direct rhs (the rhs is the `+`), so nothing here is blind but the annotation itself.
    assert(line4.exists(_.name == "Int"), line4.toString)
    assert(line4.exists(_.name == "n"), line4.toString)
    assert(line4.exists(_.name == "m"), line4.toString)
    assert(!line4.exists(_.name == "unchecked"), line4.toString)

  test("a `var` with an ascribed rhs behaves like the `val` case"):
    val src =
      """package p
        |object O:
        |  val o: Option[Int] = None
        |  var w: Int = (o: @unchecked) match { case Some(n) => n }
        |""".stripMargin
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    val line3 = toks.filter(_.line == 3)
    assert(!line3.exists(_.name == "Int"), line3.toString)
    assert(!line3.exists(_.name == "unchecked"), line3.toString)
    assert(!line3.exists(_.name == "n"), line3.toString)
    assert(line3.exists(_.name == "w"), line3.toString)
    assert(line3.exists(_.name == "o"), line3.toString)
    assert(line3.exists(_.name == "Some"), line3.toString)

  test("Scala 3 definition forms carry role=definition"):
    val src =
      """package p
        |given g: Int = 1
        |given h(using x: Int): Long = 1L
        |enum E:
        |  case A
        |""".stripMargin
    val toks = NameTokens.in(src, Nil).getOrElse(fail("did not parse"))
    def roleOfName(n: String): Option[String] = toks.find(_.name == n).map(_.role)
    assertEquals(roleOfName("g"), Some("definition"))
    assertEquals(roleOfName("h"), Some("definition"))
    assertEquals(roleOfName("E"), Some("definition"))
    assertEquals(roleOfName("A"), Some("definition"))
    assertEquals(roleOfName("Int"), Some("use"))
