package com.github.mercurievv.scalasemantic.analysis

import scala.meta.*
import scala.meta.tokenizers.Tokenized
import scala.util.Try

/** A reversible annotation layer for source text shown to an LLM. `inject` appends one
  * `SEM:...:SEM` block, wrapped as its own Scala block comment, to the end of each annotated line —
  * no line is inserted, so line numbers stay stable and can be used as a reference. `strip` removes
  * exactly that block back out, so a round trip through inject-then-strip reproduces the original
  * text. Wrapping it as a block comment keeps it valid Scala on its own, independent of whatever
  * else is already on the line (a real trailing single-line comment before it, or anything placed
  * after the block's closing delimiter), which is left untouched.
  *
  * Both directions read the text with the Scala tokenizer, so they agree on what is code: a block
  * is only ever placed where it is a comment, and only comments are stripped. A marker that is
  * string data — in a one-line literal or on any line of a multi-line one — is never touched.
  */
object SourceSentinel:

  /** Opens a sentinel block, inside its wrapping block comment. */
  val Start: String = "SEM:"

  /** Closes a sentinel block, inside its wrapping block comment. */
  val End: String = ":SEM"

  // The non-greedy body may not contain a close marker, so one block never swallows the code
  // between it and the next.
  private val BlockPattern = raw" ?/\*SEM:(?:(?!:SEM\*/).)*:SEM\*/".r

  // Where `inject` leaves blocks inside a comment token: at the very end of a `//` comment.
  private val TrailingBlocks = raw"(?: ?/\*SEM:(?:(?!:SEM\*/).)*:SEM\*/)+$$".r

  // A block that is a comment token of its own.
  private val WholeBlock = raw"/\*SEM:(?:(?!:SEM\*/).)*:SEM\*/".r

  /** One annotation to attach to `line` (0-based index into the lines passed to `inject`). */
  final case class Note(line: Int, payload: String)

  /** Append one `SEM:...:SEM` block, wrapped as its own block comment, to the end of each noted
    * line, joining multiple notes on the same line with `"; "` inside a single block, in the order
    * given. Lines with no note are returned unchanged. The result has the same line count as
    * `lines`. A note for a line that ends inside a multi-line literal moves to the line the literal
    * closes on ([[anchorOf]]): appended inside the literal it would be string content, which
    * neither compiles away nor can be told apart from the author's text.
    */
  def inject(lines: IndexedSeq[String], notes: List[Note]): IndexedSeq[String] =
    val anchor = anchorOf(lines)
    val byLine: Map[Int, List[Note]] = notes.groupBy(n => anchor(n.line))
    lines.zipWithIndex.map { case (line, i) =>
      byLine.get(i) match
        case None     => line
        case Some(ns) =>
          s"$line /*$Start${ns.map(n => commentSafe(n.payload)).mkString("; ")}$End*/"
    }

  /** The line a note for each line can safely trail: the line itself, unless its end sits inside a
    * multi-line string, interpolation or XML literal, or a multi-line comment — then the line that
    * token closes on (followed again, should that line end inside another one). Identity when the
    * text does not tokenize.
    */
  def anchorOf(lines: IndexedSeq[String]): Int => Int =
    val inside: Map[Int, Int] = tokensOf(lines.mkString("\n")).fold(Map.empty[Int, Int]) { ts =>
      ts.iterator
        .filter(t => (isLiteralPart(t) || isComment(t)) && t.pos.endLine > t.pos.startLine)
        .flatMap(t => (t.pos.startLine until t.pos.endLine).map(_ -> t.pos.endLine))
        .toMap
    }
    @annotation.tailrec
    def follow(line: Int, hops: Int): Int =
      inside.get(line) match
        case Some(next) if hops < lines.size => follow(next, hops + 1)
        case _                               => line
    line => follow(line, 0)

  /** Remove every `SEM:...:SEM` block comment (and the one space before it, if present) from every
    * line. Real content elsewhere on the line — including anything before the block or after it —
    * passes through unchanged. Only a block where `inject` puts one is removed: a comment token of
    * its own, or the tail of a `//` comment. A marker inside a string literal, or quoted mid-way
    * through a doc comment, is the author's text and stays.
    */
  def strip(lines: IndexedSeq[String]): IndexedSeq[String] =
    val text = lines.mkString("\n")
    tokensOf(text) match
      case None     => lines.map(stripLine)
      case Some(ts) =>
        val cuts = ts.iterator.filter(isComment).flatMap(c => blockCuts(text, c)).toList
        if cuts.isEmpty then lines
        else
          cuts
            .sortBy((from, _) => -from)
            .foldLeft(text) { case (acc, (from, until)) => acc.take(from) + acc.drop(until) }
            .split("\n", -1)
            .toIndexedSeq

  /** The injected blocks of one comment token, as a `[from, until)` offset range into `text`: the
    * whole token (with the space `inject` put before it) when it is a block, or the blocks that end
    * a `//` comment.
    */
  private def blockCuts(text: String, comment: Token): Option[(Int, Int)] =
    val body = comment.text
    if WholeBlock.matches(body) then
      val from = comment.start
      val spaced = from > 0 && text.charAt(from - 1) == ' '
      Some((if spaced then from - 1 else from, comment.end))
    else if body.startsWith("//") then
      TrailingBlocks
        .findFirstMatchIn(body)
        .map(m => (comment.start + m.start, comment.start + m.end))
    else None

  private def tokensOf(text: String): Option[Tokens] =
    Try(dialects.Scala3(text).tokenize).toOption.flatMap {
      case Tokenized.Success(ts) => Some(ts)
      case _                     => None
    }

  private def isComment(t: Token): Boolean =
    t match
      case _: Token.Comment => true
      case _                => false

  private def isLiteralPart(t: Token): Boolean =
    t match
      case _: Token.Constant.String | _: Token.Interpolation.Part | _: Token.Xml.Part => true
      case _                                                                          => false

  /** A payload is rendered code, which may itself contain comment delimiters (a regex literal, a
    * `/* */` in an argument). Inside the block they would close it early, or — Scala block comments
    * nest — leave it unclosed; split them so the block stays one comment.
    */
  private def commentSafe(payload: String): String =
    payload.replace("*/", "* /").replace("/*", "/ *")

  /** The fallback when the text does not tokenize: removes every sentinel block on one line EXCEPT
    * those sitting inside a one-line string literal. Blocks are removed back to front so earlier
    * indices stay valid.
    */
  private def stripLine(line: String): String =
    BlockPattern
      .findAllMatchIn(line)
      .filter(m => notInString(line, 0, m.start, inStr = false))
      .toList
      .reverse
      .foldLeft(line)((acc, m) => acc.take(m.start) + acc.drop(m.end))

  /** Whether `upTo` sits outside a `"`-delimited literal, by a deliberately naive scan: escapes are
    * honoured, but the scan never leaves the line.
    */
  @annotation.tailrec
  private def notInString(line: String, i: Int, upTo: Int, inStr: Boolean): Boolean =
    if i >= upTo then !inStr
    else
      line.charAt(i) match
        case '\\' if inStr => notInString(line, i + 2, upTo, inStr)
        case '"'           => notInString(line, i + 1, upTo, !inStr)
        case _             => notInString(line, i + 1, upTo, inStr)
