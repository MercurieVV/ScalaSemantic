package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Paths
import scala.meta.internal.semanticdb as s

class DuplicationAnalyzerSuite extends munit.FunSuite:

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
