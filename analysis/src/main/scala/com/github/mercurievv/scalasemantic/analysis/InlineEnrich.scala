package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.SourceAnnotation

/** Splices compiler-inferred information directly into source text at its real column, instead of
  * appending it as a trailing `// ⟹` comment (`annotated_source`'s `compilable` format) or a bare
  * `⟹` note (`annotated`). `val nums = List(1, 2, 3)` becomes `val nums: List[Int] = List(1, 2, 3)`
  * — real, readable Scala shape, not a comment glued to the end of the line. This is what makes
  * `source_ranges`' deep-dive view different from `annotated_source`'s: the whole point of asking
  * for a few chosen lines in full detail is to see what the compiler actually elaborated, in place.
  *
  * Three annotation kinds have a splice point computed from `character`/`endCharacter`:
  *   - `inferred-type` — INSERT `: T` right after the declared name.
  *   - `inferred-type-args` — INSERT `[T]` right after the call's own name/receiver, before its
  *     argument list (the original call is otherwise untouched).
  *   - `full` / `elaborated` (`detail=full`'s merged per-call-site note) — REPLACE the original
  *     `[character, endCharacter)` span — the exact source range of the call the note elaborates —
  *     with the fully elaborated expression, since that text already IS the whole original call
  *     rewritten (type args, resolved instances, and all); showing both would just duplicate it.
  *
  * Every other kind (`implicit`, `implicit-conversion`) needs the compiler's synthesised text
  * layered onto (not replacing) the original span, which isn't representable as a single splice —
  * those are appended after the (spliced) line as a bare `⟹` note, same convention as
  * `annotated_source`'s `annotated` format, never as a comment.
  */
object InlineEnrich:

  private val InsertKinds = Set("inferred-type", "inferred-type-args")
  private val ReplaceKinds = Set("full", "elaborated")

  /** Splice every annotation on `line` into it (see class doc for which kinds splice vs. trail).
    * `anns` must all belong to this one line; `character`/`endCharacter` are interpreted against
    * `line` as given.
    */
  def spliceLine(line: String, anns: List[SourceAnnotation]): String =
    // A note whose splice point cannot be located on this line (a def whose parameter list runs
    // onto the next line, a call whose range ends on a later line) trails instead: a misplaced
    // splice would corrupt the code shown, a trailing note only costs position.
    // A note whose anchor is not on this line at all (out-of-bounds column) is stale; drop it.
    val placed: List[Either[SourceAnnotation, (Int, Int, String)]] = anns.flatMap { a =>
      if InsertKinds.contains(a.kind) then
        insertionFor(line, a) match
          case Some((pos, text)) => List(Right((pos, pos, text)))
          case None              => Option.when(anchored(line, a))(Left(a)).toList
      else if ReplaceKinds.contains(a.kind) then
        List(
          Either.cond(
            replaceable(line, a),
            (a.character, a.endCharacter, stripReplaceLabel(a)),
            a
          )
        )
      else List(Left(a))
    }
    val splices = disjoint(placed.collect { case Right(sp) => sp })
    val trailing = placed.collect { case Left(a) => a }

    val spliced = splices
      .sortBy { case (start, _, _) => -start }
      .foldLeft(line) { case (acc, (start, end, text)) =>
        if start < 0 || start > acc.length then acc
        else
          val safeEnd = math.max(start, math.min(end, acc.length))
          acc.take(start) + text + acc.drop(safeEnd)
      }
    if trailing.isEmpty then spliced
    else spliced + " ⟹ " + trailing.map(trailingText).mkString("; ")

  /** The splices that can all apply to one line: widest span first, each one kept only if it does
    * not cut into a span already kept. A nested call's elaboration is already part of the enclosing
    * one's text; applying both would splice the inner text into the outer replacement's middle. An
    * insertion at a span's boundary does not cut into it.
    */
  private def disjoint(splices: List[(Int, Int, String)]): List[(Int, Int, String)] =
    def cuts(a: (Int, Int, String), b: (Int, Int, String)): Boolean =
      val (aStart, aEnd, _) = a
      val (bStart, bEnd, _) = b
      if aStart == aEnd then bStart < aStart && aStart < bEnd
      else if bStart == bEnd then aStart < bStart && bStart < aEnd
      else aStart < bEnd && bStart < aEnd
    splices
      .sortBy { case (start, end, _) => (start - end, start) }
      .foldLeft(List.empty[(Int, Int, String)]) { (kept, sp) =>
        if kept.exists(cuts(_, sp)) then kept else sp :: kept
      }

  /** An inferred-type note whose declared name is really at its column (only its splice point is
    * missing from the line).
    */
  private def anchored(line: String, a: SourceAnnotation): Boolean =
    a.kind == "inferred-type" && identifierLength(line, a.character) > 0

  /** A trailing note shows its text as written, except an inferred type, whose splice-ready `: T`
    * would dangle after the line; it gets the `type: T` display form.
    */
  private def trailingText(a: SourceAnnotation): String =
    if a.kind == "inferred-type" && a.text.startsWith(": ") then s"type: ${a.text.drop(2)}"
    else stripReplaceLabel(a)

  /** A REPLACE note's span must be a whole expression on this line: single-line, non-empty, in
    * bounds, and bracket-balanced. A call whose range ends on a later line carries that line's end
    * column, which here would cut the expression mid-way; and an elaboration that renders only its
    * using clause (`(using x)`, the call itself unrenderable) would replace the call with nothing.
    */
  private def replaceable(line: String, a: SourceAnnotation): Boolean =
    !a.multiLine && a.character >= 0 && a.character < line.length &&
      a.endCharacter > a.character &&
      balanced(line.substring(a.character, math.min(a.endCharacter, line.length))) &&
      !stripReplaceLabel(a).startsWith("(")

  private def balanced(text: String): Boolean =
    val pairs = Map(')' -> '(', ']' -> '[', '}' -> '{')
    text
      .foldLeft(Option(List.empty[Char])) { (stack, c) =>
        stack.flatMap { st =>
          if "([{".contains(c) then Some(c :: st)
          else
            pairs.get(c) match
              case Some(open) => st.headOption.filter(_ == open).map(_ => st.drop(1))
              case None       => Some(st)
        }
      }
      .exists(_.isEmpty)

  /** `elaborated` carries a `"elaborated: "` display prefix (added by `Analyzer.sourceAnnotations`
    * for `detail=terse`'s merged notes) that must not be pasted into code; `full` is already clean.
    */
  private def stripReplaceLabel(a: SourceAnnotation): String =
    if a.kind == "elaborated" then a.text.stripPrefix("elaborated: ") else a.text

  private def insertionFor(line: String, a: SourceAnnotation): Option[(Int, String)] =
    a.kind match
      case "inferred-type" =>
        // Analyzer.sourceAnnotations rewrites this kind's text from ": T" to the human-readable
        // "type: T" for the append-style renderers — undo that back to a splice-ready ": T".
        val ascription =
          if a.text.startsWith("type: ") then s": ${a.text.stripPrefix("type: ")}" else a.text
        val idLen = identifierLength(line, a.character)
        // A def's result type goes after its type and value parameter lists, not after its name.
        Option
          .when(idLen > 0)(a.character + idLen)
          .flatMap(afterParameterLists(line, _))
          .map(pos => (pos, ascription))
      case "inferred-type-args" =>
        val targs = trailingBracketGroup(a.text)
        Option.when(targs.nonEmpty)((a.character + (a.text.length - targs.length), targs))
      case _ => None

  /** The position after every `[...]` / `(...)` group that directly follows `from` (blanks between
    * groups allowed): the end of a def's signature before its result type. `None` when a group does
    * not close on this line — the parameter list continues on the next one.
    */
  @annotation.tailrec
  private def afterParameterLists(line: String, from: Int): Option[Int] =
    val next = line.indexWhere(c => !c.isWhitespace, from)
    if next < 0 || !"[(".contains(line.charAt(next)) then Some(from)
    else
      closingIndex(line, next) match
        case Some(close) => afterParameterLists(line, close + 1)
        case None        => None

  /** Index of the bracket closing the one at `open` on this line, if any. */
  private def closingIndex(line: String, open: Int): Option[Int] =
    @annotation.tailrec
    def scan(i: Int, depth: Int): Option[Int] =
      if i >= line.length then None
      else
        line.charAt(i) match
          case '(' | '[' | '{' => scan(i + 1, depth + 1)
          case ')' | ']' | '}' => if depth == 1 then Some(i) else scan(i + 1, depth - 1)
          case _               => scan(i + 1, depth)
    scan(open, 0)

  /** Length of the identifier starting at `start` in `line` (letters, digits, `_`); 0 if `start` is
    * out of bounds or not the start of an identifier.
    */
  private def identifierLength(line: String, start: Int): Int =
    if start < 0 || start >= line.length then 0
    else line.substring(start).takeWhile(c => c.isLetterOrDigit || c == '_').length

  /** The trailing `[...]` group of `text` (matching from the last `]` back to its own `[`, so a
    * nested group like `[List[Int]]` is returned whole) — empty if `text` does not end in `]`.
    */
  private def trailingBracketGroup(text: String): String =
    @annotation.tailrec
    def scan(i: Int, depth: Int): Int =
      if i < 0 then -1
      else
        text.charAt(i) match
          case ']' => scan(i - 1, depth + 1)
          case '[' => if depth == 1 then i else scan(i - 1, depth - 1)
          case _   => scan(i - 1, depth)
    if !text.endsWith("]") then ""
    else
      val start = scan(text.length - 1, 0)
      if start < 0 then "" else text.substring(start)
