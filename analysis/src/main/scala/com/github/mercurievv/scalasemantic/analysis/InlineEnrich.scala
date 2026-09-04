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
    val inserts = anns
      .filter(a => InsertKinds.contains(a.kind))
      .flatMap(insertionFor(line, _))
      .map { case (pos, text) => (pos, pos, text) }
    val replaces = anns
      .filter(a => ReplaceKinds.contains(a.kind))
      .map(a => (a.character, a.endCharacter, stripReplaceLabel(a)))
    val trailing =
      anns.filterNot(a => InsertKinds.contains(a.kind) || ReplaceKinds.contains(a.kind))

    val spliced = (inserts ++ replaces)
      .sortBy { case (start, _, _) => -start }
      .foldLeft(line) { case (acc, (start, end, text)) =>
        if start < 0 || start > acc.length then acc
        else
          val safeEnd = math.max(start, math.min(end, acc.length))
          acc.take(start) + text + acc.drop(safeEnd)
      }
    if trailing.isEmpty then spliced
    else spliced + " ⟹ " + trailing.map(_.text).mkString("; ")

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
        Option.when(idLen > 0)((a.character + idLen, ascription))
      case "inferred-type-args" =>
        val targs = trailingBracketGroup(a.text)
        Option.when(targs.nonEmpty)((a.character + (a.text.length - targs.length), targs))
      case _ => None

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
