package com.github.mercurievv.scalasemantic.analysis

import stainless.annotation.pure
import stainless.lang.*

/** The analysis engine's collection-free numeric & geometric kernels — the small primitive
  * decisions that the larger, collection-heavy algorithms delegate to. Kept in one dependency-free
  * object for a single reason: this file IS the Stainless verification target.
  *
  * `sbt stainlessVerify` runs the standalone Stainless tool (v0.9.9.3, from
  * https://github.com/epfl-lara/stainless/releases/tag/v0.9.9.3) over THIS file directly — there is
  * no separate mirror. The functions here are the exact code that runs in production; their
  * `require`/`ensuring` contracts are formally discharged rather than re-stated on a copy that
  * could drift. The collection-bearing callers ([[graph.GraphMetrics]], [[AnalyzerHelpers]]) can't
  * be extracted by the standalone tool (it doesn't model `.iterator`/`.view`/`.groupBy`), so the
  * verifiable logic is pulled down to here and the callers delegate.
  *
  * Verified members: [[instability]], [[pageRankBase]], [[rangeContains]], [[rangeSpan]],
  * [[nextLevel]] (the `rangeSpan` nonlinear multiplication-overflow VC times out under the bundled
  * `smt-z3` and is tolerated as `unknown`; it is `valid` under native Z3).
  *
  * This object imports `stainless.lang.*` so [[pageRankBase]] can use stainless's IEEE-754 `Double`
  * model (`.isNaN`) — the only not-NaN witness the verifier accepts. The unmanaged
  * `stainless-library.jar` is therefore bundled into the `mcp` fat jar (see `build.sbt`). Under the
  * import, `require`/`ensuring` are stainless's erased ghost variants (no runtime cost), and `==>`
  * is available — but the boolean clauses below are kept as `!A || B` for readability parity with
  * [[graph.GraphMetrics]].
  */
object PureKernels:

  /** Instability metric Ce/(Ca+Ce), or 0 for an isolated node (no edges either way).
    *
    * Verified contract:
    *   - *Precondition*: Ca and Ce are non-negative AND `ca + ce` does not overflow `Int` (the
    *     `ca <= Int.MaxValue - ce` bound — Stainless found that overflow in `ca + ce` invalidates
    *     the postcondition otherwise; e.g. ca = 2147483640, ce = 8).
    *   - *Postcondition*: result ∈ [0.0, 1.0], with both boundaries pinned exactly — no out-edges ⟹
    *     0.0 (maximally stable), only out-edges ⟹ 1.0 (maximally unstable). Pinning the boundaries
    *     (not just the range) is what catches an operand swap in the formula.
    */
  @pure
  def instability(ca: Int, ce: Int): Double =
    require(ca >= 0 && ce >= 0 && ca <= Int.MaxValue - ce)
    (if ca + ce == 0 then 0.0 else ce.toDouble / (ca + ce)).ensuring(r =>
      r >= 0.0 && r <= 1.0 &&
        (ce != 0 || r == 0.0) && // ce == 0  ==> r == 0.0
        (ca != 0 || ce == 0 || r == 1.0) // ca == 0 && ce > 0  ==> r == 1.0
    )

  /** PageRank teleport term `(1 - damping) / n`: the rank mass every node receives each iteration
    * regardless of the link structure. It is a probability, so it must lie in [0.0, 1.0].
    *
    * Verified contract:
    *   - *Precondition*: `damping` is a probability in [0, 1] and not NaN, and `n > 0`. The
    *     `!damping.isNaN` guard is REQUIRED and uses stainless's IEEE-754 `Double` model: without
    *     it Stainless reports a `Comparison with NaN` counter-example (`damping = NaN`), since
    *     every bound comparison against NaN is false and the postcondition becomes ill-defined. (A
    *     primitive `damping == damping` does NOT work as a substitute — Stainless must already know
    *     an operand is non-NaN to evaluate that comparison, so the guard would be circular; only
    *     `.isNaN` is accepted, which is why this object imports `stainless.lang`.) The sole caller
    *     passes the literal 0.85, so the NaN case is unreachable in practice, but the contract
    *     makes the assumption explicit.
    *   - *Postcondition*: result ∈ [0.0, 1.0].
    */
  @pure
  def pageRankBase(damping: Double, n: Int): Double =
    require(!damping.isNaN && damping >= 0.0 && damping <= 1.0 && n > 0)
    ((1.0 - damping) / n).ensuring(r => r >= 0.0 && r <= 1.0)

  /** Whether a 0-based `(line, character)` position falls inside the half-open range
    * `[(startLine, startChar), (endLine, endChar))` — inclusive of the start, exclusive of the end.
    * The range fields are passed as primitives so the function is collection-free;
    * [[AnalyzerHelpers.rangeContains]] is the thin `s.Range` adapter that calls this.
    *
    * Verified contract:
    *   - *Precondition*: the range is well-formed (`start <= end`, both non-negative) and the
    *     queried position is non-negative.
    *   - *Postcondition*: the result is exactly `afterStart && beforeEnd`; a contained position
    *     lies within the range's line span; and an EMPTY range (`start == end`) contains nothing —
    *     the half-open invariant at its degenerate point, which a naive `<=`/`<=` would get wrong.
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
        (!res || (line >= startLine && line <= endLine)) && // res ==> inside line span
        (!(startLine == endLine && startChar == endChar) || !res) // empty range contains nothing
    )

  /** A range's span as a single sortable key: lines dominate (×10000), columns break ties. Used by
    * `typeAtPosition` to pick the most specific (smallest-span) occurrence covering a position, so
    * the key MUST stay non-negative — a negative span from overflow would make `minByOption` prefer
    * a bogus occurrence. [[AnalyzerHelpers.rangeSpan]] is the thin `s.Range` adapter.
    *
    * Computed in `Long`: the original `Int` form `(endLine - startLine) * 10000 + (endChar -
    * startChar)` is UNSOUND — Stainless finds a concrete `Addition overflow` counter-example that
    * flips the result negative. Widening the line term to `Long` makes overflow impossible for any
    * 32-bit position (the maximum, ~2^31·10^4, is far inside `Long`).
    *
    * Verified contract: well-formed non-negative range, with a multi-line column delta no more
    * negative than -10000 (columns never shrink by more than a line's worth), ⟹ result ≥ 0. The
    * overflow VCs are `valid`; the nonlinear multiplication VC is `unknown` (timeout) under the
    * bundled `smt-z3`, `valid` under native Z3 — tolerated by `scripts/stainless-verify.sh`.
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

  /** One step of the longest-path layering recurrence: given the level accumulated so far for a
    * node and the level of one of the nodes it depends on, the node's level is at least one more
    * than that child's. Extracted from [[graph.GraphMetrics.layers]], where it is folded over a
    * node's out-edges to compute its depth.
    *
    * Verified contract:
    *   - *Precondition*: both inputs are non-negative and `childLevel` leaves room to add 1 without
    *     overflowing `Int` (guarding the same overflow class that made the `Int` `rangeSpan`
    *     unsound).
    *   - *Postcondition*: the result is ≥ the running accumulator AND ≥ `childLevel + 1` (so the
    *     longest chain wins and depth strictly increases through a dependency) AND non-negative.
    */
  @pure
  def nextLevel(acc: Int, childLevel: Int): Int =
    require(acc >= 0 && childLevel >= 0 && childLevel < Int.MaxValue)
    (if acc >= childLevel + 1 then acc else childLevel + 1).ensuring(r =>
      r >= acc && r >= childLevel + 1 && r >= 0
    )
