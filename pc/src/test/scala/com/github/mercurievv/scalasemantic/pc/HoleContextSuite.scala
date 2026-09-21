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
      |  def render(a: Int, s: String, xs: Seq[String]): String = @@1???
      |  def total(a: Int): Int = @@2???
      |  val untyped = @@3???
      |  def takes(n: Int, io: IO[Unit]): Unit = ()
      |  def call: Unit = takes(1, @@4???)
      |  def effect: IO[Unit] = @@5???
      |  def shown(using Show[Int]): String = summon[Show[Int]].show(@@6count)
      |  // a comment @@7 here
      |  def seqs: Seq[String] = @@8???
      |""".stripMargin

  private def marker(n: Int): (String, Int) =
    val tag = s"@@$n"
    val stripped = marked.replaceAll("@@\\d", "")
    val before = marked.indexOf(tag)
    val offset = marked.substring(0, before).replaceAll("@@\\d", "").length
    (stripped, offset)

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
