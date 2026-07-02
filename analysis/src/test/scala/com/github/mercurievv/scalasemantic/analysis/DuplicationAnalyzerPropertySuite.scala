package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.file.Paths
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
