package com.github.mercurievv.scalasemantic.analysis

/** A reversible annotation layer for source text shown to an LLM. `inject` appends one
  * `SEM:...:SEM` block, wrapped as its own Scala block comment, to the end of each annotated line —
  * no line is inserted, so line numbers stay stable and can be used as a reference. `strip` removes
  * exactly that block back out, so a round trip through inject-then-strip reproduces the original
  * text. Wrapping it as a block comment keeps it valid Scala on its own, independent of whatever
  * else is already on the line (a real trailing single-line comment before it, or anything placed
  * after the block's closing delimiter), which is left untouched.
  */
object SourceSentinel:

  /** Opens a sentinel block, inside its wrapping block comment. */
  val Start: String = "SEM:"

  /** Closes a sentinel block, inside its wrapping block comment. */
  val End: String = ":SEM"

  // The non-greedy body may not contain a close marker, so one block never swallows the code
  // between it and the next.
  private val BlockPattern = raw" ?/\*SEM:(?:(?!:SEM\*/).)*:SEM\*/".r

  /** One annotation to attach to `line` (0-based index into the lines passed to `inject`). */
  final case class Note(line: Int, payload: String)

  /** Append one `SEM:...:SEM` block, wrapped as its own block comment, to the end of each noted
    * line, joining multiple notes on the same line with `"; "` inside a single block, in the order
    * given. Lines with no note are returned unchanged. The result has the same line count as
    * `lines`.
    */
  def inject(lines: IndexedSeq[String], notes: List[Note]): IndexedSeq[String] =
    val byLine: Map[Int, List[Note]] = notes.groupBy(_.line)
    lines.zipWithIndex.map { case (line, i) =>
      byLine.get(i) match
        case None     => line
        case Some(ns) => s"$line /*$Start${ns.map(_.payload).mkString("; ")}$End*/"
    }

  /** Remove every `SEM:...:SEM` block comment (and the one space before it, if present) from every
    * line. Real content elsewhere on the line — including anything before the block or after it —
    * passes through unchanged.
    */
  def strip(lines: IndexedSeq[String]): IndexedSeq[String] =
    lines.map(stripLine)

  /** Removes every sentinel block on one line EXCEPT those sitting inside a string literal: a
    * source may legitimately contain the marker as data (`val marker = "/*SEM:x:SEM*/"`), and
    * stripping that wrote the file back as `val marker = ""` — silent loss of the author's text.
    * Blocks are removed back to front so earlier indices stay valid.
    */
  private def stripLine(line: String): String =
    BlockPattern
      .findAllMatchIn(line)
      .filter(m => notInString(line, 0, m.start, inStr = false))
      .toList
      .reverse
      .foldLeft(line)((acc, m) => acc.take(m.start) + acc.drop(m.end))

  /** Whether `upTo` sits outside a `"`-delimited literal, by a deliberately naive scan: escapes are
    * honoured, but the scan never leaves the line, so a marker inside a MULTI-line string is still
    * treated as code on its continuation lines.
    */
  @annotation.tailrec
  private def notInString(line: String, i: Int, upTo: Int, inStr: Boolean): Boolean =
    if i >= upTo then !inStr
    else
      line.charAt(i) match
        case '\\' if inStr => notInString(line, i + 2, upTo, inStr)
        case '"'           => notInString(line, i + 1, upTo, !inStr)
        case _             => notInString(line, i + 1, upTo, inStr)
