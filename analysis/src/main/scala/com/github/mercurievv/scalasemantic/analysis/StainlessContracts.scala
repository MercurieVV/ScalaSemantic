package com.github.mercurievv.scalasemantic.analysis

import stainless.annotation.pure
import stainless.lang.*

/** Self-contained Stainless verification targets extracted from the analysis engine.
  *
  * This file contains only primitive, collection-free functions whose contracts can be formally
  * verified by the Stainless standalone tool. `stainless.lang.*` brings in the `==>` implication
  * operator and makes `require`/`ensuring` the Stainless ghost variants (no runtime overhead)
  * rather than Predef's exception-throwing versions.
  *
  * To run verification:
  * {{{
  *   /path/to/stainless-0.9.9.3/stainless \
  *     analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/StainlessContracts.scala
  * }}}
  *
  * The Stainless standalone tool (v0.9.9.3, mac-arm64) is available from:
  * https://github.com/epfl-lara/stainless/releases/tag/v0.9.9.3 The `sbt-stainless` plugin from the
  * same release is sbt-1.x only and cannot load under sbt 2.0.
  *
  * `instability`, `pageRankBase` and `rangeContains` are fully discharged (`valid`). For
  * `rangeSpan`, every overflow VC the bundled solver can reach is `valid` — but its nonlinear
  * `Long` multiplication-overflow VC (and the postcondition that depends on it) come back `unknown`
  * (timeout) under the `smt-z3` fallback this build ships; the native Z3 backend discharges them.
  * Where a precondition looks over-cautious (the `!isNaN` guards, the `Int.MaxValue - ce` overflow
  * bound) it is because Stainless produced a concrete counter-example without it — those guards
  * encode real, if pathological, failure modes of the mirrored production code, not ceremony.
  */
object StainlessContracts:

  /** Instability metric: Ce/(Ca+Ce), or 0 when the node is isolated (no in- or out-edges).
    *
    * Verified properties:
    *   - *Precondition*: both afferent coupling (Ca) and efferent coupling (Ce) are non-negative
    *     AND their sum does not overflow 32-bit signed range — negative edge counts are
    *     meaningless, and Stainless checks integer arithmetic for overflow by default (without the
    *     `ca <= Int.MaxValue - ce` bound it finds e.g. ca = 2147483640, ce = 8 overflows `ca + ce`,
    *     invalidating both the addition and the postcondition).
    *   - *Postcondition*: result ∈ [0.0, 1.0] (the standard instability invariant) AND the two
    *     boundary semantics are pinned exactly: a node with no out-edges is maximally stable (`ce ==
    *     0 ⟹ 0.0`); a node with only out-edges is maximally unstable (`ca == 0 && ce > 0 ⟹ 1.0`).
    *     Pinning the boundaries (not just the range) is what catches a sign- or operand-swap bug in
    *     the formula — a contract that only asserts `[0,1]` would pass for `ca/(ca+ce)` too.
    *
    * Mirrors [[com.github.mercurievv.scalasemantic.analysis.graph.GraphMetrics.instability]].
    */
  @pure
  def instability(ca: Int, ce: Int): Double =
    require(ca >= 0 && ce >= 0 && ca <= Int.MaxValue - ce)
    (if ca + ce == 0 then 0.0 else ce.toDouble / (ca + ce)).ensuring(r =>
      r >= 0.0 && r <= 1.0 &&
        ((ce == 0) ==> (r == 0.0)) &&
        ((ca == 0 && ce > 0) ==> (r == 1.0))
    )

  /** PageRank teleport term `(1 - d) / n`: the rank mass every node receives unconditionally each
    * iteration. It is a probability, so it must land in [0.0, 1.0].
    *
    * Verified properties:
    *   - *Precondition*: `d` is a damping factor — a probability in [0.0, 1.0] — and the node count
    *     `n` is strictly positive. `!damping.isNaN` is REQUIRED: Stainless models IEEE-754 and
    *     reports a `Comparison with NaN` counter-example (`damping = NaN`) otherwise, because every
    *     `>=`/`<=` against NaN is false and the bound comparisons become ill-defined. The
    *     production caller only ever passes 0.85, but the contract makes the assumption explicit.
    *   - *Postcondition*: result ∈ [0.0, 1.0].
    *
    * Mirrors the `base` term of
    * [[com.github.mercurievv.scalasemantic.analysis.graph.GraphMetrics.pageRank]].
    */
  @pure
  def pageRankBase(damping: Double, n: Int): Double =
    require(!damping.isNaN && damping >= 0.0 && damping <= 1.0 && n > 0)
    ((1.0 - damping) / n).ensuring(r => r >= 0.0 && r <= 1.0)

  /** Whether a 0-based `(line, character)` position falls inside a half-open range `[start, end)` —
    * inclusive of the start, exclusive of the end.
    *
    * Verified properties:
    *   - *Precondition*: the range is well-formed (`start <= end`, both non-negative) and the
    *     queried position is non-negative.
    *   - *Postcondition*: the result is exactly `afterStart && beforeEnd`; a contained position
    *     lies within the range's line span; and an EMPTY range (`start == end`) contains nothing —
    *     the half-open invariant at its degenerate point, which a naive `<=`/`<=` implementation
    *     would get wrong by reporting the single point as contained.
    *
    * Mirrors [[com.github.mercurievv.scalasemantic.analysis.AnalyzerHelpers.rangeContains]] (which
    * takes an `s.Range`; the fields are inlined here so the function is collection-free).
    */
  @pure
  def rangeContains(
      startLine: Int,
      startChar: Int,
      endLine: Int,
      endChar: Int,
      line: Int,
      character: Int
  ): Boolean =
    require(
      startLine >= 0 && startChar >= 0 && endLine >= startLine &&
        (endLine > startLine || endChar >= startChar) && line >= 0 && character >= 0
    )
    val afterStart = line > startLine || (line == startLine && character >= startChar)
    val beforeEnd = line < endLine || (line == endLine && character < endChar)
    (afterStart && beforeEnd).ensuring(res =>
      (res == (afterStart && beforeEnd)) &&
        (res ==> (line >= startLine && line <= endLine)) &&
        ((startLine == endLine && startChar == endChar) ==> !res)
    )

  /** Range span as a single sortable key: lines dominate (×10000), columns break ties. Used by
    * `typeAtPosition` to pick the most specific (smallest-span) occurrence covering a position, so
    * the key MUST be non-negative for a well-formed range — a negative span from overflow would
    * make `minByOption` prefer a bogus occurrence.
    *
    * THE `Int` FORM IS UNSOUND. The original production code computed
    * `(endLine - startLine) * 10000 + (endChar - startChar)` in `Int`; Stainless finds a concrete
    * `Addition overflow` counter-example that flips the result negative, violating `res >= 0`.
    * Widening the line term to `Long` removes the overflow for every 32-bit position, which is the
    * fix applied to [[com.github.mercurievv.scalasemantic.analysis.AnalyzerHelpers.rangeSpan]].
    *
    * Contract (this `Long` form — overflow VCs `valid`; the nonlinear multiplication-overflow VC
    * and the postcondition are `unknown`/timeout under the bundled `smt-z3`, `valid` under native
    * Z3):
    *   - *Precondition*: well-formed, non-negative range; a multi-line range's column delta is not
    *     more negative than -10000 (columns never shrink by more than a line's worth), so the line
    *     term dominates and the span stays non-negative.
    *   - *Postcondition*: result ≥ 0.
    */
  @pure
  def rangeSpan(startLine: Int, endLine: Int, startChar: Int, endChar: Int): Long =
    require(
      startLine >= 0 && endLine >= startLine &&
        startChar >= 0 && endChar >= 0 &&
        (if endLine == startLine then endChar >= startChar
         else endChar - startChar >= -10000)
    )
    ((endLine.toLong - startLine) * 10000L + (endChar.toLong - startChar))
      .ensuring(res => res >= 0L)

  // joinFqn and packageDotted are annotated @pure in AnalyzerHelpers for documentation and
  // tooling; they are not included here because Stainless 0.9.9.3 does not support
  // String.isEmpty / String.replace — those methods have no Stainless equivalents in the
  // standard library mapping.
