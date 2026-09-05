package com.github.mercurievv.scalasemantic.analysis

import scala.annotation.tailrec

/** A unified diff between two versions of the same file.
  *
  * `SourceView.renderDiff` diffs the source against its *enriched* rendering, where the two sides
  * are aligned line for line by construction, so it needs no diff algorithm. A write is the other
  * shape: the edited buffer may have added, removed and moved lines, so alignment has to be
  * computed. This is that computation — an LCS over the lines that actually differ, with the common
  * head and tail trimmed first (an agent's edit is usually a few lines in a long file, so the table
  * is built over a handful of lines, not the whole source).
  *
  * Pure and total: no I/O, no exceptions, `""` when the two versions are identical.
  */
object SourceDiff:

  /** How many lines of unchanged context surround each hunk, as in `diff -U3`. */
  val DefaultContext = 3

  /** Past this many differing lines on a side, the LCS table costs more than the alignment is worth
    * to a reader, and the window is reported as one replaced block instead. That is still a correct
    * unified diff — just a coarser one — and it keeps a pathological input (a file rewritten end to
    * end) from turning into a quadratic table.
    */
  private val LcsWindowCap = 2000

  private enum Op:
    case Keep(line: String)
    case Del(line: String)
    case Add(line: String)

  def unified(
      path: String,
      oldLines: IndexedSeq[String],
      newLines: IndexedSeq[String],
      context: Int = DefaultContext
  ): String =
    if oldLines == newLines then ""
    else
      val prefix = oldLines.zip(newLines).takeWhile((a, b) => a == b).length
      // Prefix and suffix must not claim the same line twice on the shorter side.
      val room = math.min(oldLines.size, newLines.size) - prefix
      val suffix =
        math.min(room, oldLines.reverse.zip(newLines.reverse).takeWhile((a, b) => a == b).length)
      val oldWindow = oldLines.slice(prefix, oldLines.size - suffix)
      val newWindow = newLines.slice(prefix, newLines.size - suffix)
      val windowOps =
        if math.max(oldWindow.size, newWindow.size) > LcsWindowCap then
          oldWindow.map(Op.Del.apply).toList ++ newWindow.map(Op.Add.apply).toList
        else align(oldWindow.toVector, newWindow.toVector)
      val ops =
        oldLines.take(prefix).map(Op.Keep.apply).toList ++
          windowOps ++
          oldLines.takeRight(suffix).map(Op.Keep.apply).toList
      hunks(path, ops, math.max(0, context))

  /** Longest-common-subsequence alignment of two line windows.
    *
    * `table(i)(j)` is the LCS length of `a.drop(i)` and `b.drop(j)`, built back to front so the
    * forward walk can always take the branch that keeps the longer common run. Elements are read
    * through `lift` rather than `apply`: out of range is a real state of the walk (one side
    * exhausted), not an error, and `SeqApply` is a compile error in this build.
    */
  private def align(a: Vector[String], b: Vector[String]): List[Op] =
    val lastRow = Vector.fill(b.size + 1)(0)
    val table: Vector[Vector[Int]] =
      a.indices.reverse.foldLeft(Vector(lastRow)) { (rows, i) =>
        val below = rows.headOption.getOrElse(lastRow)
        val row = b.indices.reverse.foldLeft(Vector(0)) { (acc, j) =>
          val cell =
            if a.lift(i) == b.lift(j) then below.lift(j + 1).getOrElse(0) + 1
            else math.max(below.lift(j).getOrElse(0), acc.headOption.getOrElse(0))
          cell +: acc
        }
        row +: rows
      }

    def lcs(i: Int, j: Int): Int = table.lift(i).flatMap(_.lift(j)).getOrElse(0)

    @tailrec
    def walk(i: Int, j: Int, acc: List[Op]): List[Op] =
      if i >= a.size && j >= b.size then acc.reverse
      else if i >= a.size then walk(i, j + 1, b.lift(j).map(Op.Add.apply).toList ++ acc)
      else if j >= b.size then walk(i + 1, j, a.lift(i).map(Op.Del.apply).toList ++ acc)
      else if a.lift(i) == b.lift(j) then
        walk(i + 1, j + 1, a.lift(i).map(Op.Keep.apply).toList ++ acc)
      // Deletions before additions on a tie, so a replaced line reads `-old` then `+new`.
      else if lcs(i + 1, j) >= lcs(i, j + 1) then
        walk(i + 1, j, a.lift(i).map(Op.Del.apply).toList ++ acc)
      else walk(i, j + 1, b.lift(j).map(Op.Add.apply).toList ++ acc)

    walk(0, 0, Nil)

  /** Group the ops into `@@` hunks, each padded with `context` unchanged lines, merging hunks whose
    * context would overlap or touch.
    */
  private def hunks(path: String, ops: List[Op], context: Int): String =
    val changed = ops.zipWithIndex.collect {
      case (_: Op.Del, i) => i
      case (_: Op.Add, i) => i
    }
    if changed.isEmpty then ""
    else
      val n = ops.size
      val ranges = changed
        .map(i => (math.max(0, i - context), math.min(n - 1, i + context)))
        .foldLeft(List.empty[(Int, Int)]) { (acc, r) =>
          acc match
            case (s0, e0) :: tail if r._1 <= e0 + 1 => (s0, math.max(e0, r._2)) :: tail
            case _                                  => r :: acc
        }
        .reverse
      val header = s"--- $path (on disk)\n+++ $path (written)"
      val rendered = ranges.map { (s, e) =>
        val before = ops.take(s)
        val slice = ops.slice(s, e + 1)
        def oldSide(l: List[Op]) = l.count {
          case _: Op.Add => false
          case _         => true
        }
        def newSide(l: List[Op]) = l.count {
          case _: Op.Del => false
          case _         => true
        }
        val oldCount = oldSide(slice)
        val newCount = newSide(slice)
        // A unified-diff hunk header is 1-based, and 0-length sides start at the preceding line.
        val oldStart = if oldCount == 0 then oldSide(before) else oldSide(before) + 1
        val newStart = if newCount == 0 then newSide(before) else newSide(before) + 1
        val body = slice.map {
          case Op.Keep(l) => s" $l"
          case Op.Del(l)  => s"-$l"
          case Op.Add(l)  => s"+$l"
        }
        (s"@@ -$oldStart,$oldCount +$newStart,$newCount @@" :: body).mkString("\n")
      }
      (header :: rendered).mkString("\n")
