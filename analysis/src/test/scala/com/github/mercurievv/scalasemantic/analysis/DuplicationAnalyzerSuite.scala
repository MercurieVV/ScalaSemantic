package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Paths
import scala.meta.*
import scala.meta.internal.semanticdb as s

class DuplicationAnalyzerSuite extends munit.FunSuite:

  // two identical method bodies in different files/dirs — a cross-file duplicate.
  private def dupIndex = SemanticIndex(
    Vector(
      s.TextDocument(
        uri = "a/foo.scala",
        text = "object Foo:\n  def add(x: Int, y: Int): Int =\n    val sum = x + y\n    sum\n"
      ),
      s.TextDocument(
        uri = "b/bar.scala",
        text =
          "object Bar:\n  def plus(a: Double, b: Double): Double =\n    val total = a + b\n    total\n"
      )
    )
  )

  test("DuplicationAnalyzer detects smart code duplications across methods") {
    val doc1 = s.TextDocument(
      uri = "foo.scala",
      text = """package test
          |
          |object Foo:
          |  def add(x: Int, y: Int): Int =
          |    val sum = x + y
          |    sum
          |""".stripMargin
    )
    val doc2 = s.TextDocument(
      uri = "bar.scala",
      text = """package test
          |
          |object Bar:
          |  def plus(a: Double, b: Double): Double =
          |    val total = a + b
          |    total
          |""".stripMargin
    )

    val index = SemanticIndex(Vector(doc1, doc2))
    val root = Paths.get(".")
    val result = DuplicationAnalyzer.analyze(index, root, minSize = 10)

    assertEquals(result.groups.size, 1)
    val group = result.groups.head
    assertEquals(group.size, 2)
    assert(group.astNodeCount >= 10)

    val occurrences = group.occurrences
    assertEquals(occurrences.map(_.location.uri).toSet, Set("foo.scala", "bar.scala"))
    assertEquals(occurrences.map(_.enclosingMethod), List(None, None))
  }

  test("DuplicationAnalyzer filters out subsumed duplicate blocks") {
    val doc1 = s.TextDocument(
      uri = "foo.scala",
      text = """package test
          |
          |object Foo:
          |  def method1(x: Int): Int = {
          |    val a = x * 2
          |    val b = a + 3
          |    b
          |  }
          |""".stripMargin
    )
    val doc2 = s.TextDocument(
      uri = "bar.scala",
      text = """package test
          |
          |object Bar:
          |  def method2(y: Int): Int = {
          |    val a = y * 2
          |    val b = a + 3
          |    b
          |  }
          |""".stripMargin
    )

    val index = SemanticIndex(Vector(doc1, doc2))
    val root = Paths.get(".")
    val result = DuplicationAnalyzer.analyze(index, root, minSize = 10)

    assertEquals(result.groups.size, 1)
    val group = result.groups.head
    assertEquals(group.occurrences.map(_.enclosingMethod), List(None, None))
  }

  test("DuplicationAnalyzer reports independent duplicated sub-blocks in different methods") {
    val doc1 = s.TextDocument(
      uri = "foo.scala",
      text = """package test
          |
          |object Foo:
          |  def method1(x: Int): Int = {
          |    val prefix = x + 1
          |    {
          |      val a = x * 2
          |      val b = a + 3
          |      b
          |    }
          |  }
          |""".stripMargin
    )
    val doc2 = s.TextDocument(
      uri = "bar.scala",
      text = """package test
          |
          |object Bar:
          |  def method2(y: Int): Int = {
          |    val prefix = y - 2
          |    {
          |      val a = y * 2
          |      val b = a + 3
          |      b
          |    }
          |  }
          |""".stripMargin
    )

    val index = SemanticIndex(Vector(doc1, doc2))
    val root = Paths.get(".")
    val result = DuplicationAnalyzer.analyze(index, root, minSize = 10)

    assertEquals(result.groups.size, 1)
    val group = result.groups.head
    assertEquals(group.occurrences.map(_.enclosingMethod), List(Some("method1"), Some("method2")))
  }

  test("normalize: locals → varN, literals → Lit, types → Type, names kept, comma-joined") {
    val block = dialects.Scala3("{ val a: Int = x + 1; a }").parse[Stat].get
    val out = DuplicationAnalyzer.normalize(block, None)
    assert(out.contains("Term.Name(var0)"), s"local 'a' is placeholdered as var0: $out")
    assert(out.contains("Term.Name(x)"), s"a free name is kept verbatim: $out")
    assert(out.contains("Lit"), s"the literal 1 renders as Lit: $out")
    assert(out.contains("Type"), s"the Int ascription renders as Type: $out")
    assert(out.contains(","), s"sibling children are comma-joined: $out")
  }

  test("normalize: the enclosing-method's own name becomes the 'root' placeholder") {
    // a recursive call to the enclosing def: its name must normalize to `root`, not its literal name.
    val defn = dialects.Scala3("def fac(n: Int): Int = fac(n)").parse[Stat].get
    val out = DuplicationAnalyzer.normalize(defn, None)
    assert(out.contains("Term.Name(root)"), s"the def's self-reference is 'root': $out")
  }

  test("analyze: minSize is an inclusive floor, not always-true") {
    val index = dupIndex
    val root = Paths.get(".")
    // an absurd floor admits nothing — proves the size guard is real (not mutated to always-true).
    assert(DuplicationAnalyzer.analyze(index, root, minSize = 10000).groups.isEmpty)
    // at a floor equal to the largest duplicated block's own node count, that block still qualifies.
    val n = DuplicationAnalyzer.analyze(index, root, minSize = 10).groups.head.astNodeCount
    assert(
      DuplicationAnalyzer.analyze(index, root, minSize = n).groups.nonEmpty,
      s"a block of exactly $n nodes is included (>=, not >)"
    )
  }

  test("analyze: a non-empty pathFilter scopes the files considered") {
    val index = dupIndex
    val root = Paths.get(".")
    assert(
      DuplicationAnalyzer
        .analyze(index, root, minSize = 10, pathFilter = Some("a/"))
        .groups
        .isEmpty,
      "restricting to a/ leaves only one file, so the cross-file duplicate vanishes"
    )
    assert(
      DuplicationAnalyzer.analyze(index, root, minSize = 10, pathFilter = Some("")).groups.nonEmpty,
      "an empty filter means no filtering — the duplicate is still found"
    )
  }
