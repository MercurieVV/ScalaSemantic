package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.file.Paths
import scala.meta.*
import scala.meta.internal.semanticdb as s

/** Property-based complement to [[DuplicationAnalyzerSuite]] for the `minSize` size guard.
  *
  * [[DuplicationAnalyzerSuite]] pins the "inclusive floor" behaviour at two hand-picked points (an
  * absurdly high floor admits nothing; a floor equal to the block's own node count still admits
  * it). This suite generalises that to the full boundary contract: `minSize` only *filters* the
  * fixed set of duplicate blocks the fixture contains, so `analyze(minSize).groups.nonEmpty` holds
  * exactly when `minSize` is at most the largest duplicated block's node count. Raising the floor
  * can only remove blocks (never add larger ones), so the largest block size is a stable threshold.
  */
class DuplicationAnalyzerPropertySuite extends munit.ScalaCheckSuite:

  // two identical method bodies in different files — the same cross-file duplicate fixture the
  // example suite uses.
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

  private val root = Paths.get(".")

  // The largest duplicated block's node count for this fixture. minSize=1 is the most permissive
  // floor, so it surfaces every duplicate — its maximum is the true threshold.
  private val maxBlockSize: Int =
    DuplicationAnalyzer.analyze(dupIndex, root, minSize = 1).groups.map(_.astNodeCount).max

  property("analyze(minSize).groups.nonEmpty holds iff minSize <= the largest duplicated block"):
    forAll(Gen.chooseNum(1, maxBlockSize * 2)) { m =>
      DuplicationAnalyzer
        .analyze(dupIndex, root, minSize = m)
        .groups
        .nonEmpty == (m <= maxBlockSize)
    }

  // ============================ normalize renaming-invariance ==========================
  //
  // The whole point of normalize's structural fingerprint is that two occurrences of "the same"
  // code, differing only in local variable names and literal values, normalize to the same string.
  // DuplicationAnalyzerSuite checks the format's shape (`Term.Name(varN)`, `Lit`, `Type`,
  // comma-joining) for one hand-picked snippet; these properties generalise over an arbitrary
  // choice of names/literals and assert the two resulting fingerprints are *equal* — the actual
  // invariant a renaming mutant (e.g. leaking the raw name through instead of `varN`) would break.

  private val keywords = Set(
    "val",
    "var",
    "def",
    "if",
    "then",
    "else",
    "match",
    "case",
    "for",
    "yield",
    "new",
    "this",
    "true",
    "false",
    "null",
    "type",
    "given",
    "extension",
    "import",
    "package",
    "private",
    "protected",
    "override",
    "final",
    "sealed",
    "trait",
    "object",
    "class",
    "enum",
    "export",
    "try",
    "catch",
    "finally",
    "throw",
    "super",
    "implicit",
    "lazy",
    "return",
    "while",
    "do",
    "with",
    "extends"
  )

  private val genIdent: Gen[String] =
    Gen
      .choose(1, 6)
      .flatMap(n => Gen.listOfN(n, Gen.alphaLowerChar).map(_.mkString))
      .suchThat(name => name.nonEmpty && !keywords.contains(name))

  private val genDistinctPair: Gen[(String, String)] =
    for
      a <- genIdent
      b <- genIdent.suchThat(_ != a)
    yield (a, b)

  private val genLit: Gen[Int] = Gen.choose(0, 999)

  private def normalizeOf(code: String): String =
    DuplicationAnalyzer.normalize(dialects.Scala3(code).parse[Stat].get, None)

  private def blockCode(n1: String, n2: String, l1: Int, l2: Int): String =
    s"{ val $n1: Int = $l1; val $n2 = $n1 + $l2; $n2 }"

  property(
    "normalize of a local-var block is invariant to the local names and literal values chosen"
  ):
    forAll(genDistinctPair, genLit, genLit, genDistinctPair, genLit, genLit) {
      case ((n1a, n2a), l1a, l2a, (n1b, n2b), l1b, l2b) =>
        normalizeOf(blockCode(n1a, n2a, l1a, l2a)) == normalizeOf(blockCode(n1b, n2b, l1b, l2b))
    }

  private def recursiveDefCode(fname: String, pname: String): String =
    s"def $fname($pname: Int): Int = $fname($pname)"

  property("normalize maps a recursive def's self-reference to 'root', for any def/param names"):
    forAll(genDistinctPair) { case (fname, pname) =>
      normalizeOf(recursiveDefCode(fname, pname)).contains("Term.Name(root)")
    }

  property("normalize of a recursive def is invariant to the def/param names chosen"):
    forAll(genDistinctPair, genDistinctPair) { case ((f1, p1), (f2, p2)) =>
      normalizeOf(recursiveDefCode(f1, p1)) == normalizeOf(recursiveDefCode(f2, p2))
    }
