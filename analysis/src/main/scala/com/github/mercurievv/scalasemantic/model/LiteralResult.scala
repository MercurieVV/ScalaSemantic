package com.github.mercurievv.scalasemantic.model

import upickle.default.*

/** The nearest definition enclosing a literal (spec 2026-09-25-purrism-loop-and-memorisation-design
  * §1). It is what lets a caller judge a CONSTRUCT rather than a line: a table that memorises test
  * inputs is one `val` or one `def`, whatever its length. `kind` is one of `def`, `val`, `var`,
  * `object`, `class`, `trait`, `enum`, `given`; `line` is the definition's own 0-based start line.
  */
final case class Enclosing(kind: String, name: String, line: Int) derives ReadWriter

/** One literal the source text contains. Syntax only.
  *
  *   - `kind` is `int`, `long`, `double`, `float`, `string` or `char`. Booleans, `null` and `()`
  *     are never reported: they carry no signal about what a program was written to recognise.
  *   - `value` is normalised so the same literal written two ways compares equal: `0x10` and `16`
  *     are both `16`, `1_000` is `1000`, and a string's escapes are resolved.
  *   - `enclosing` is absent when no definition encloses the literal.
  */
final case class Literal(
    line: Int,
    character: Int,
    kind: String,
    value: String,
    enclosing: Option[Enclosing]
) derives ReadWriter

/** Every literal of one file's requested ranges, in source order. `omitted` counts the literals the
  * 2,000-per-file bound dropped. `error` is set — with `literals` empty — when the file could not
  * be read or parsed: zero literals and a syntax error are different answers and must not be
  * confused.
  */
final case class FileLiterals(
    uri: String,
    literals: List[Literal],
    omitted: Int,
    error: Option[String]
) derives ReadWriter

/** The wire result: one entry per file asked about. */
final case class LiteralResult(files: List[FileLiterals]) derives ReadWriter
