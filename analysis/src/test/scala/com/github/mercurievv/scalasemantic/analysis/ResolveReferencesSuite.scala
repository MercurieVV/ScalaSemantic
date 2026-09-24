package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** The join (spec 2026-09-24-diff-scoped-verification-design §1): scalameta's name tokens against
  * the presentation compiler's occurrences. What no single backend can answer is here — a name the
  * source mentions and the compiler never resolved.
  */
class ResolveReferencesSuite extends munit.FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration("120s")

  private val workspace = java.nio.file.Files.createTempDirectory("resolve-refs").nn
  private val file = workspace.resolve("Refs.scala").nn
  private val uri = file.toUri

  private val src =
    """package demo
      |
      |object Refs:
      |  val label: String = "x"
      |  def good: String = label.trim
      |  def bad: String = label.trimm
      |  def missing: String = nowhereAtAll
      |""".stripMargin

  java.nio.file.Files.writeString(file, src)

  private def refs(ranges: (Int, Int)*) =
    PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      val az = Analyzer(new SemanticIndex(Vector.empty), Some(backend))
      az.resolveReferences(uri, src, "Refs.scala", ranges).getOrElse(fail("no PC backend"))
    }

  test("a resolved stdlib reference carries its symbol and origin") {
    val r = refs((4, 5))
    val trim = r.references.find(_.name == "trim").getOrElse(fail(r.references.toString))
    assert(trim.resolved, trim.toString)
    assert(
      trim.symbol.exists(_.startsWith("scala/")) || trim.symbol.exists(_.startsWith("java/")),
      trim.toString
    )
    assertEquals(trim.origin, Some("stdlib"))
  }

  test("an unresolved member is reported with a near-miss suggestion") {
    val r = refs((5, 6))
    val bad = r.references.find(_.name == "trimm").getOrElse(fail(r.references.toString))
    assert(!bad.resolved, bad.toString)
    assertEquals(bad.symbol, None)
    assert(bad.suggestions.exists(_.contains("trim")), bad.suggestions.toString)
  }

  test("an unresolved free name is reported with no suggestion when nothing is close") {
    val r = refs((6, 7))
    val missing = r.references.find(_.name == "nowhereAtAll").getOrElse(fail(r.references.toString))
    assert(!missing.resolved, missing.toString)
    assertEquals(missing.suggestions, Nil)
  }

  test("references outside the ranges never appear") {
    val r = refs((6, 7))
    assert(!r.references.exists(_.name == "trimm"), r.references.toString)
    assert(!r.references.exists(_.name == "good"), r.references.toString)
  }

  test("a definition's own name is role=definition and resolves") {
    val r = refs((3, 4))
    val label = r.references.find(_.name == "label").getOrElse(fail(r.references.toString))
    assertEquals(label.role, "definition")
    assert(label.resolved, label.toString)
  }

  test("a syntax error is reported as an error, not as zero references") {
    val broken = "object X:\n  def f(: Int = 1\n"
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(new SemanticIndex(Vector.empty), Some(backend))
        .resolveReferences(uri, broken, "Refs.scala", Seq((0, 2)))
        .getOrElse(fail("no PC backend"))
    }
    assert(r.error.exists(_.nonEmpty), r.toString)
    assertEquals(r.references, Nil)
  }

  test("the 200-row bound is honoured and counted") {
    val many = (1 to 120).map(i => s"  val v$i: String = label").mkString("\n")
    val big = s"package demo\n\nobject Big:\n  val label: String = \"x\"\n$many\n"
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(new SemanticIndex(Vector.empty), Some(backend))
        .resolveReferences(uri, big, "Refs.scala", Nil)
        .getOrElse(fail("no PC backend"))
    }
    assertEquals(r.references.size, 200)
    assert(r.omitted > 0, r.omitted.toString)
  }
