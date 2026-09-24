package com.github.mercurievv.scalasemantic.pc

import HoleContext.*

class HoleContextSuite extends munit.FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration("120s")

  private val workspace = java.nio.file.Files.createTempDirectory("hole-ctx").nn
  private val file = workspace.resolve("Holes.scala").nn
  private val uri = file.toUri

  // The buffer under test: several hole shapes in one file. `@@` marks positions in the tests below
  // (stripped before the compiler sees the text), so a line/col never has to be counted by hand.
  private val marked =
    """package demo
      |
      |import cats.effect.IO
      |
      |trait Show[A]:
      |  def show(a: A): String
      |object Show:
      |  given Show[Int] = (a: Int) => a.toString
      |
      |object Holes:
      |  val label: String = "x"
      |  val count: Int = 3
      |  val names: List[String] = Nil
      |  val sh: Show[Int] = @@0???
      |  def render(a: Int, s: String, xs: Seq[String]): String = @@1???
      |  def total(a: Int): Int = @@2???
      |  val untyped = @@3???
      |  def takes(n: Int, io: IO[Unit]): Unit = ()
      |  def call: Unit = takes(1, @@4???)
      |  def effect: IO[Unit] = @@5???
      |  def shown(using Show[Int]): String = summon[Show[Int]].show(@@6count)
      |  // a comment @@7 here
      |  def seqs: Seq[String] = @@8???
      |  def branchy = if count > 0 then @@9??? else 1
      |""".stripMargin

  /** Strips every `@@n` tag from `src` and returns the stripped text with the offset `@@n` marked —
    * so a variant of the buffer (e.g. one with an error injected before the hole) never needs its
    * offset recomputed by hand.
    */
  private def markerIn(src: String, n: Int): (String, Int) =
    val tag = s"@@$n"
    val stripped = src.replaceAll("@@\\d", "")
    val before = src.indexOf(tag)
    val offset = src.substring(0, before).replaceAll("@@\\d", "").length
    (stripped, offset)

  private def marker(n: Int): (String, Int) = markerIn(marked, n)

  java.nio.file.Files.writeString(file, marked.replaceAll("@@\\d", ""))

  private val holes = new HoleContext(HoleContext.runtimeClasspath, Nil)
  override def afterAll(): Unit = holes.close()

  private def at(n: Int): Result =
    val (code, off) = marker(n)
    holes.at(uri, code, off)

  test("??? as a def result: exact expected type, why names the def") {
    val r = at(1)
    assert(r.found)
    assertEquals(r.hole.map(_.kind), Some("???"))
    assertEquals(r.expected.map(_.confidence), Some(Confidence.Exact))
    assertEquals(r.expected.flatMap(_.tpe), Some("String"))
    assertEquals(r.expected.map(_.why), Some("result type of def render"))
  }

  test("val without a declared type: unconstrained, no candidates") {
    val r = at(3)
    assertEquals(r.expected.map(_.confidence), Some(Confidence.Unconstrained))
    assertEquals(r.expected.flatMap(_.tpe), None)
    assert(r.expected.exists(_.why.contains("declare")))
    assertEquals(r.bindings, Nil); assertEquals(r.producers, Nil); assertEquals(r.givens, Nil)
  }

  test("??? as a method argument: the parameter's type") {
    val r = at(4)
    assertEquals(r.expected.flatMap(_.tpe), Some("IO[Unit]"))
    assertEquals(r.expected.map(_.why), Some("argument 2 of takes"))
  }

  test("an expression position that is not ???") {
    val r = at(6)
    assertEquals(r.hole.map(_.kind), Some("expression"))
    assertEquals(r.expected.flatMap(_.tpe), Some("Int"))
  }

  test("a position in a comment: not found") {
    val r = at(7)
    assert(!r.found)
    assert(r.why.exists(_.nonEmpty))
  }

  test("hole text is the source slice, not the qualified tree") {
    assertEquals(at(6).hole.map(_.text), Some("count"))
    assertEquals(at(1).hole.map(_.text), Some("???"))
  }

  test("inferred def result through an if: unconstrained") {
    val r = at(9)
    assertEquals(r.expected.map(_.confidence), Some(Confidence.Unconstrained))
    assertEquals(r.expected.flatMap(_.tpe), None)
  }

  test("bindings: conforming params and members listed, non-conforming excluded, kinds right") {
    val r = at(1) // expected String
    val names = r.bindings.map(b => b.name -> b.kind)
    assert(names.contains("s" -> "param"), names.toString)
    assert(names.contains("label" -> "val"), names.toString)
    assert(!r.bindings.exists(_.name == "a"), r.bindings.toString) // Int does not conform to String
    assert(!r.bindings.exists(_.name == "count"), r.bindings.toString)
    assert(r.bindings.exists(b => b.name == "s" && b.from.contains("render")))
  }

  test("subtype binding accepted: List[String] fits Seq[String]") {
    val r = at(8)
    assert(r.bindings.exists(_.name == "names"), r.bindings.toString)
  }

  test("producers: companion members whose result fits, exact before shape, with signatures") {
    val r = at(5) // expected IO[Unit]
    val byName = r.producers.map(p => p.name -> p.matchKind).toMap
    assertEquals(byName.get("IO.unit"), Some("exact"))
    assertEquals(byName.get("IO.pure"), Some("shape"))
    assert(r.producers.find(_.name == "IO.pure").exists(_.signature.contains("IO[A]")))
    val firstShape = r.producers.indexWhere(_.matchKind == "shape")
    val lastExact = r.producers.lastIndexWhere(_.matchKind == "exact")
    assert(firstShape < 0 || lastExact < firstShape, r.producers.map(_.matchKind).toString)
  }

  test("givens: the instance for a typeclass expected type") {
    // `val sh: Show[Int] = ???` — the expected type at the hole is the typeclass itself.
    val r = at(0)
    assert(r.givens.exists(g => g.tpe == "Show[Int]"), r.givens.toString)
  }

  test("types: the expected type's class, with a SemanticDB symbol") {
    val r = at(5)
    assert(r.types.exists(_.symbol == "cats/effect/IO#"), r.types.toString)
    assert(r.types.exists(_.signature.contains("IO")))
  }

  test("bounds honoured with omitted counts") {
    val many = (1 to 30).map(i => s"  val s$i: String = \"\"").mkString("\n")
    val code = s"object Many:\n$many\n  def f: String = ???\n"
    val off = code.lastIndexOf("???")
    val r = holes.at(uri, code, off)
    assertEquals(r.bindings.size, HoleContext.maxBindings)
    assert(r.omitted.bindings >= 10, r.omitted.toString)
  }

  test("a method-local type is omitted from types rather than a bogus symbol") {
    val code =
      """object LocalTypes:
        |  def use(): Unit =
        |    case class Local(x: Int)
        |    val v: Local = ???
        |""".stripMargin
    val off = code.indexOf("???")
    val r = holes.at(uri, code, off)
    assertEquals(r.types, Nil)
  }

  test("a type name that is not a plain identifier is backtick-escaped in its symbol") {
    val code = "object ConsTypes:\n  val v: ::[Int] = ???\n"
    val off = code.indexOf("???")
    val r = holes.at(uri, code, off)
    assert(r.types.exists(_.symbol.contains("`::`")), r.types.toString)
  }

  test("an offset past the end of the file is clamped, not sent to the compiler") {
    val (code, _) = marker(1)
    val tooFar = holes.at(uri, code, code.length + 1)
    assert(!tooFar.found)
    assert(tooFar.why.exists(_.contains("outside the file")), tooFar.why.toString)
    val negative = holes.at(uri, code, -1)
    assert(!negative.found)
    assert(negative.why.exists(_.contains("outside the file")), negative.why.toString)
  }

  test("a type error elsewhere in the buffer does not stop the answer") {
    // the injected error sits before the hole, so the offset is re-derived from the marker
    val (broken, off) =
      markerIn(marked.replace("val count: Int = 3", "val count: Int = \"oops\""), 1)
    val r = holes.at(uri, broken, off)
    assert(r.found)
    assertEquals(r.expected.flatMap(_.tpe), Some("String"))
  }

  test("I1: an inaccessible companion member is never offered as a producer") {
    val code = "object Str:\n  def f: String = ???\n"
    val r = holes.at(uri, code, code.indexOf("???"))
    // package-private JDK internals on java.lang.String's companion
    assert(!r.producers.exists(_.name.contains("newStringUTF8NoRepl")), r.producers.toString)
    val code2 =
      """object Secret:
        |  class Cfg(val n: Int)
        |  object Cfg:
        |    private def hidden: Cfg = new Cfg(0)
        |    def visible: Cfg = new Cfg(1)
        |object UseSecret:
        |  def make: Secret.Cfg = ???
        |""".stripMargin
    val r2 = holes.at(uri, code2, code2.indexOf("???"))
    assert(r2.producers.exists(_.name.endsWith(".visible")), r2.producers.toString)
    assert(!r2.producers.exists(_.name.endsWith(".hidden")), r2.producers.toString)
  }

  test("I4: a case class's synthetic apply is the exact producer for its own type") {
    val code =
      """object Cases:
        |  case class P(n: Int)
        |  val p: P = ???
        |""".stripMargin
    val r = holes.at(uri, code, code.indexOf("???"))
    assert(
      r.producers.exists(p => p.name.endsWith("P.apply") && p.matchKind == "exact"),
      r.producers.toString
    )
  }

  test("I2: an un-imported companion is offered under a name that compiles (qualified)") {
    val code =
      """object NoImport:
        |  def effect: cats.effect.IO[Unit] = ???
        |""".stripMargin
    val r = holes.at(uri, code, code.indexOf("???"))
    assert(r.producers.exists(_.name == "cats.effect.IO.unit"), r.producers.toString)
    assert(!r.producers.exists(_.name == "IO.unit"), r.producers.toString)
    // and with the import in scope the short name is used
    assertEquals(at(5).producers.map(_.name).find(_.endsWith("IO.unit")), Some("IO.unit"))
  }

  test("I2: a shadowed binding is not listed under a name that resolves elsewhere") {
    val code =
      """object Shadow:
        |  val v: String = "outer"
        |  def f(v: Int): String =
        |    val x: String = ???
        |    x
        |""".stripMargin
    val r = holes.at(uri, code, code.indexOf("???"))
    // the enclosing `v: Int` shadows the String member: `v` must not be offered as a String
    assert(!r.bindings.exists(_.name == "v"), r.bindings.toString)
    assertEquals(r.bindings.map(_.name).distinct.size, r.bindings.size, r.bindings.toString)
  }

  test("I5: a hole in a non-last block statement is unconstrained; the result expression is not") {
    val stat = "object Stat:\n  def f: String =\n    ???\n    \"x\"\n"
    val rs = holes.at(uri, stat, stat.indexOf("???"))
    assertEquals(rs.expected.map(_.confidence), Some(Confidence.Unconstrained))
    assertEquals(rs.expected.flatMap(_.tpe), None)
    assertEquals(rs.producers, Nil)
    val last = "object Last:\n  def f: String =\n    val a = 1\n    ???\n"
    val rl = holes.at(uri, last, last.indexOf("???"))
    assertEquals(rl.expected.map(_.confidence), Some(Confidence.Exact))
    assertEquals(rl.expected.flatMap(_.tpe), Some("String"))
    assertEquals(rl.expected.map(_.why), Some("result type of def f"))
  }

  test("??? as an argument of a generic method: the parameter type after instantiation") {
    val code =
      """object Generic:
        |  def use[A](a: A, f: A => String): String = f(a)
        |  def call: String = use(1, ???)
        |""".stripMargin
    val r = holes.at(uri, code, code.indexOf("???"))
    assertEquals(r.expected.flatMap(_.tpe), Some("Int => String"))
    assertEquals(r.expected.map(_.why), Some("argument 2 of use"))
  }

  test("omitted.producers counts what the per-match-kind budget cut") {
    val r = at(5) // expected IO[Unit]: IO's companion has more than the budget of exact members
    val shown = r.producers.count(_.matchKind == "exact")
    assert(shown <= HoleContext.maxProducers, r.producers.size.toString)
    assert(r.producers.count(_.matchKind == "shape") <= HoleContext.maxProducers)
    assert(r.omitted.producers >= 0)
    // arithmetic: nothing is reported omitted unless a budget actually bit
    if r.omitted.producers > 0 then
      assertEquals(
        shown == HoleContext.maxProducers || r.producers.count(
          _.matchKind == "shape"
        ) == HoleContext.maxProducers,
        true
      )
  }
