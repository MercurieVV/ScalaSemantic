package com.github.mercurievv.scalasemantic.wire.api

import io.circe.Encoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.SchemaType

/** `{symbol, found:false}` — the symbol did not resolve. `found` is always `false`; server wrappers
  * key empty-result hints off it.
  */
final case class NotFoundResult(
    @description("the symbol that did not resolve") symbol: String,
    @description("always false") found: Boolean
) derives Encoder.AsObject,
      Schema

/** `{candidates, note}` — a file path matched several symbols; re-call with one of them. */
final case class CandidatesResult(
    @description("symbols defined by the file; re-call with exactly one") candidates: List[String],
    @description("how to proceed") note: String
) derives Encoder.AsObject,
      Schema

object CandidatesResult:
  def of(symbols: List[String]): CandidatesResult =
    CandidatesResult(symbols, "file defines multiple symbols; re-call with one symbol")

/** One reference location, with surrounding source lines when `contextLines` > 0. */
final case class UsageHitOut(
    @description("uri:line:col") location: String,
    @description("surrounding source lines") context: Option[List[String]]
) derives Encoder.AsObject,
      Schema

/** The three outcomes of a tool that accepts a symbol or a file path: nothing resolved, the file is
  * ambiguous, or exactly one symbol answered. Encoded untagged — the wire shape is each variant's
  * own object, discriminated by its fields (`found:false`, `candidates`).
  */
enum Lookup[+A]:
  case Found(value: A)
  case Missing(symbol: String)
  case Ambiguous(candidates: List[String])

object Lookup:
  given [A](using enc: Encoder[A]): Encoder[Lookup[A]] = Encoder.instance:
    case Found(a)         => enc(a)
    case Missing(s)       => Encoder[NotFoundResult].apply(NotFoundResult(s, found = false))
    case Ambiguous(names) => Encoder[CandidatesResult].apply(CandidatesResult.of(names))

  given [A](using found: Schema[A]): Schema[Lookup[A]] =
    Schema(
      SchemaType.SCoproduct[Lookup[A]](
        List(found, Schema.derived[NotFoundResult], Schema.derived[CandidatesResult]),
        None
      )(_ => None)
    )

/** How a source location is written on the wire: the compact `uri:line:col` string (0-based). */
object Locations:
  def format(l: com.github.mercurievv.scalasemantic.model.Location): String =
    s"${l.uri}:${l.range.start.line}:${l.range.start.character}"
