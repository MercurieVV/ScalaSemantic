package com.github.mercurievv.scalasemantic.analysis

/** Parses a compact multi-target, multi-range selector string used by tools that want the
  * compiler's full elaborated detail for a few chosen line spans instead of a whole file — e.g.
  * after skimming a file's terse `annotated_source` output, request `detail=full` for just the
  * lines that need a closer look. Grammar: `entry (";" entry)*` where
  * `entry := target "[" range (";" range)* "]"` and `range := start "-" end` (1-based, inclusive on
  * both ends). `target` is either a file uri (contains "/", or ends in a known source extension) or
  * a dotted FQN/simple name resolved via the symbol index by the caller. Example:
  * `scala.String[10-20;30-40];com/mercurievv/Olo.scala[50-55]`.
  */
object RangeSelector:

  /** One requested line span, 1-based and inclusive on both ends. */
  final case class LineRange(startLine: Int, endLine: Int)

  /** One target (file uri or FQN) with the line spans requested inside it. */
  final case class Entry(target: String, ranges: List[LineRange])

  private val EntryPattern = raw"([^\[\];]+)\[([^\]]*)\]".r
  private val RangePattern = raw"(\d+)-(\d+)".r
  private val FileExtensions = List(".scala", ".sc", ".mill")

  /** True when `target` should be resolved as a literal file path rather than a symbol/FQN: it
    * contains a path separator, or already carries a known source-file extension.
    */
  def looksLikeFileTarget(target: String): Boolean =
    target.contains("/") || FileExtensions.exists(target.endsWith)

  /** Parse the whole selector string, or the first problem found: empty input, text that is not
    * part of any `target[...]` entry, an empty target, or a malformed/inverted range.
    */
  def parse(query: String): Either[String, List[Entry]] =
    val trimmed = query.trim
    if trimmed.isEmpty then Left("empty range selector")
    else
      val matches = EntryPattern.findAllMatchIn(trimmed).toList
      val reconstructed = matches.map(_.matched).mkString(";")
      if matches.isEmpty || reconstructed != trimmed then
        Left(s"could not parse '$query' as 'target[N-M;...]' entries separated by ';'")
      else parseEntries(matches)

  private def parseEntries(
      matches: List[scala.util.matching.Regex.Match]
  ): Either[String, List[Entry]] =
    matches.foldLeft[Either[String, List[Entry]]](Right(Nil)) { (acc, m) =>
      acc.flatMap { entries =>
        val target = m.group(1).trim
        if target.isEmpty then Left(s"empty target in '${m.matched}'")
        else parseRanges(m.group(2)).map(ranges => entries ++ List(Entry(target, ranges)))
      }
    }

  private def parseRanges(raw: String): Either[String, List[LineRange]] =
    val parts = raw.split(";", -1).toList.map(_.trim).filter(_.nonEmpty)
    if parts.isEmpty then Left(s"no ranges in '[$raw]'")
    else
      parts.foldLeft[Either[String, List[LineRange]]](Right(Nil)) { (acc, p) =>
        acc.flatMap { ranges =>
          p match
            case RangePattern(s, e) =>
              val start = s.toInt
              val end = e.toInt
              if end < start then Left(s"range '$p' has end before start")
              else Right(ranges ++ List(LineRange(start, end)))
            case other => Left(s"invalid range '$other', expected 'N-M'")
        }
      }
