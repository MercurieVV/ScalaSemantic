package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.meta.internal.semanticdb as s

/** Property-based tests for the pure helper methods in [[AnalyzerHelpers]] that are stateless or
  * only depend on the empty index. These are fast, filter-safe, and cover behaviours that the
  * example-based [[AnalyzerHelpersSuite]] expresses as a fixed list of concrete inputs.
  *
  * Properties covered:
  *   - **rangeContains**: the inclusive-start/exclusive-end containment contract expressed as
  *     forAll (replaces four concrete boundary examples with a single predicate).
  *   - **isImplicit**: the IMPLICIT-bit test is true iff the bit is set, for all bitmask values.
  *   - **packageDotted**: output never contains slashes; empty input yields empty output.
  *   - **joinFqn**: identity laws and separator placement.
  *   - **globMatcher with None**: always returns true (replaces a single concrete example).
  *
  * Note: `rangeContains` has a Stainless-verified `require` that the s.Range must be well-formed
  * (`endLine >= startLine`; if same line `endChar >= startChar`; all values non-negative) and the
  * query position must also be non-negative. Generators therefore always produce valid inputs.
  */
class AnalyzerHelpersPropertySuite extends munit.ScalaCheckSuite:

  private val h = AnalyzerHelpers(SemanticIndex.fromRoots(Nil))
  private val IMPLICIT_BIT = s.SymbolInformation.Property.IMPLICIT.value

  // ---------------------------------------------------------------------------
  // Generator helpers that satisfy rangeContains preconditions
  // ---------------------------------------------------------------------------

  /** A well-formed s.Range: all fields non-negative, endLine >= startLine, if single line then
    * endChar >= startChar. The `require` in PureKernels.rangeContains enforces exactly this.
    */
  private val genValidRange: Gen[s.Range] =
    for
      sl <- Gen.chooseNum(0, 8)
      sc <- Gen.chooseNum(0, 8)
      el <- Gen.chooseNum(0, 8).map(d => sl + d)   // endLine >= startLine
      ec <- Gen.chooseNum(0, 8).flatMap { d =>
        if el == sl then Gen.const(sc + d) // endChar >= startChar on same line
        else Gen.chooseNum(0, 8)
      }
    yield s.Range(sl, sc, el, ec)

  /** A query position: both fields non-negative. */
  private val genQueryPos: Gen[(Int, Int)] =
    for
      ql <- Gen.chooseNum(0, 12)
      qc <- Gen.chooseNum(0, 12)
    yield (ql, qc)

  // ---------------------------------------------------------------------------
  // rangeContains
  // ---------------------------------------------------------------------------

  property("rangeContains satisfies the full inclusive-start / exclusive-end contract"):
    forAll(genValidRange, genQueryPos) { (r, pos) =>
      val ql = pos._1
      val qc = pos._2
      val expected =
        if ql < r.startLine then false
        else if ql > r.endLine then false
        else if ql == r.startLine && ql == r.endLine then qc >= r.startCharacter && qc < r.endCharacter
        else if ql == r.startLine then qc >= r.startCharacter
        else if ql == r.endLine then qc < r.endCharacter
        else true
      h.rangeContains(r, ql, qc) == expected
    }

  property("rangeContains: a point at exactly the start position is always inside (unless empty range)"):
    forAll(genValidRange) { r =>
      val startIsInside = h.rangeContains(r, r.startLine, r.startCharacter)
      // An empty (degenerate) range where start == end contains nothing
      val isEmpty = r.startLine == r.endLine && r.startCharacter == r.endCharacter
      if isEmpty then !startIsInside
      else startIsInside
    }

  property("rangeContains: a point strictly before the start line is never inside"):
    forAll(genValidRange, Gen.chooseNum(0, 8)) { (r, qc) =>
      if r.startLine == 0 then true // can't go before line 0
      else !h.rangeContains(r, r.startLine - 1, qc)
    }

  property("rangeContains: the end position (endLine, endChar) is never inside"):
    forAll(genValidRange) { r =>
      !h.rangeContains(r, r.endLine, r.endCharacter)
    }

  property("rangeContains: a point strictly after the end line is never inside"):
    forAll(genValidRange, Gen.chooseNum(0, 8)) { (r, qc) =>
      !h.rangeContains(r, r.endLine + 1, qc)
    }

  // ---------------------------------------------------------------------------
  // isImplicit
  // ---------------------------------------------------------------------------

  property("isImplicit: true iff the IMPLICIT bit is set in properties"):
    forAll(Gen.chooseNum(0, 255)) { bits =>
      val info = s.SymbolInformation(symbol = "a/x.", properties = bits)
      h.isImplicit(info) == ((bits & IMPLICIT_BIT) != 0)
    }

  property("isImplicit: setting only the IMPLICIT bit makes it true"):
    val info = s.SymbolInformation(symbol = "a/x.", properties = IMPLICIT_BIT)
    h.isImplicit(info)

  property("isImplicit: zero properties means not implicit"):
    val info = s.SymbolInformation(symbol = "a/x.", properties = 0)
    !h.isImplicit(info)

  // ---------------------------------------------------------------------------
  // packageDotted
  // ---------------------------------------------------------------------------

  property("packageDotted: output contains no slashes"):
    forAll(Gen.listOf(Gen.alphaLowerChar).map(_.mkString)) { seg =>
      val pkg = if seg.isEmpty then "" else s"com/$seg/"
      !h.packageDotted(pkg).contains("/")
    }

  property("packageDotted: empty string yields empty string"):
    h.packageDotted("") == ""

  property("packageDotted: a two-segment package produces dot-separated output"):
    h.packageDotted("com/example/") == "com.example"

  // ---------------------------------------------------------------------------
  // joinFqn
  // ---------------------------------------------------------------------------

  property("joinFqn: empty pkg yields just the name"):
    forAll(Gen.alphaStr.suchThat(_.nonEmpty)) { name =>
      h.joinFqn("", name) == name
    }

  property("joinFqn: non-empty pkg is dot-separated from name"):
    forAll(
      Gen.alphaStr.suchThat(_.nonEmpty),
      Gen.alphaStr.suchThat(_.nonEmpty)
    ) { (pkg, name) =>
      val joined = h.joinFqn(pkg, name)
      joined == s"$pkg.$name"
    }

  property("joinFqn: result contains both pkg and name"):
    forAll(
      Gen.alphaStr.suchThat(_.nonEmpty),
      Gen.alphaStr.suchThat(_.nonEmpty)
    ) { (pkg, name) =>
      val joined = h.joinFqn(pkg, name)
      joined.contains(pkg) && joined.contains(name)
    }

  // ---------------------------------------------------------------------------
  // globMatcher
  // ---------------------------------------------------------------------------

  property("globMatcher(None) always returns true"):
    forAll(Gen.alphaNumStr) { s =>
      h.globMatcher(None)(s)
    }

  property("globMatcher(Some(literal)) returns true iff the string contains the literal"):
    forAll(Gen.alphaStr.suchThat(_.nonEmpty), Gen.alphaStr.suchThat(_.nonEmpty)) {
      (lit, haystack) =>
        // Only test the non-wildcard case (no '*' in pattern)
        if !lit.contains("*") then
          val matcher = h.globMatcher(Some(lit))
          matcher(haystack) == haystack.contains(lit)
        else true
    }
