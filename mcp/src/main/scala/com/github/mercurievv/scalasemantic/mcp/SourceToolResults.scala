package com.github.mercurievv.scalasemantic.mcp

import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.SchemaType

// Wire shapes of the source/position tools. Unlike the typed tools (see `TypedTool`), these tools
// still build their JSON with shared helpers (`SourceView.result`, `renderRange`, …) that several
// goldens pin, so the types here describe that wire shape for `outputSchema` only. Conformance
// tests run the real tools and validate their output against these schemas, so the two cannot
// drift apart unnoticed.

/** `type_at_position` hit: the most specific symbol at the position. */
final case class PositionHit(
    @description("SemanticDB symbol string — pass it to the other tools") symbol: String,
    @description("display name") name: String,
    @description("rendered type of the symbol at the position") `type`: String,
    @description("true when answered by the presentation compiler (file not in the index)")
    pcFallback: Option[Boolean],
    @description("why the answer may have gaps (with pcFallback)") pcFallbackHint: Option[String]
) derives Schema

/** `{found:false}` — nothing at that position. */
final case class PositionMissing(@description("always false") found: Boolean) derives Schema

final case class SearchHit(
    @description("document uri") uri: String,
    @description("0-based line") line: Int,
    @description("the matching line's text") text: String
) derives Schema

final case class SearchTextResult(
    @description("the query as given") query: String,
    @description("number of hits returned") count: Int,
    @description("hits in file order, capped by `limit`") hits: List[SearchHit]
) derives Schema

/** A read of (a slice of) a file: `annotated_source`, `symbol_source`, `source_around_position`. */
final case class SourceViewResult(
    @description("document uri") uri: String,
    @description("annotated | compilable | plain | diff | enriched") format: String,
    @description("number of compiler notes in `source`") annotationCount: Int,
    @description("how to read `source`") legend: String,
    @description("the rendered source") source: String,
    @description("sha256 of the whole file; pass as baseHash when writing it back")
    sha256: Option[String],
    @description("false/true: whether the file changed since the compile the notes come from")
    staleIndex: Option[Boolean],
    @description("what a stale index means (with staleIndex: true)") staleHint: Option[String],
    @description("true when answered by the presentation compiler (file not in the index)")
    pcFallback: Option[Boolean],
    @description("why the answer may have gaps (with pcFallback)") pcFallbackHint: Option[String],
    @description("an ambiguous name resolved to its first match (symbol_source)")
    note: Option[String]
) derives Schema

/** `annotated_source` in write mode. */
final case class SourceWriteResult(
    @description("document uri") uri: String,
    @description("always true") written: Boolean,
    @description("bytes saved") bytesWritten: Int,
    @description("sha256 of the saved file") sha256: String,
    @description("whether the text differs from before") changed: Boolean,
    @description("unified diff of the change") diff: String
) derives Schema

/** `{uri, found:false}` — the file is unknown or missing on disk. */
final case class UriNotFound(
    @description("the uri that did not resolve") uri: String,
    @description("always false") found: Boolean,
    @description("true when answered by the presentation compiler") pcFallback: Option[Boolean],
    @description("why the answer may have gaps (with pcFallback)") pcFallbackHint: Option[String]
) derives Schema

/** `document_outline`. Entries nest through [[OutlineEntryOut]]. */
final case class DocumentOutlineResult(
    @description("document uri") uri: String,
    @description("true when outlined from the supplied `source` buffer") liveSource: Option[
      Boolean
    ],
    @description("true when narrowed by query/symbol/kind/maxDepth") filtered: Option[Boolean],
    @description("declarations nested by scope") outline: List[OutlineEntryOut],
    @description("true when answered by the presentation compiler") pcFallback: Option[Boolean],
    @description("why the answer may have gaps (with pcFallback)") pcFallbackHint: Option[String]
)

/** One outline node. Recursive: must not `derives Schema` (see [[RecursiveSchemaDerivation]]). */
final case class OutlineEntryOut(
    @description("display name") name: String,
    @description("CLASS, TRAIT, OBJECT, METHOD, …") kind: String,
    @description("0-based definition line") line: Int,
    @description("rendered signature") signature: Option[String],
    @description("SemanticDB symbol string") symbol: String,
    @description("nested declarations") children: Option[List[OutlineEntryOut]]
)

object Alternatives:
  /** An untagged union: the wire value is exactly one of the alternatives' own objects. */
  def of(alts: List[Schema[?]]): Schema[Any] =
    Schema(SchemaType.SCoproduct[Any](alts, None)(_ => None))

/** Attaches the `outputSchema` of the tools whose bodies still build JSON by hand. */
object SourceToolSchemas:

  private def schema[A](using s: Schema[A]): ujson.Value = OutputSchema.of[A]

  private val schemaOf: Map[String, ujson.Value] =
    import OutlineSchemas.given
    val notFound = Schema.derived[NotFoundResult]
    val uriNotFound = Schema.derived[UriNotFound]
    val sourceView = Schema.derived[SourceViewResult]
    Map(
      "type_at_position" -> schema(using
        Alternatives.of(List(Schema.derived[PositionHit], Schema.derived[PositionMissing]))
      ),
      "search_text" -> schema[SearchTextResult],
      "document_outline" -> schema(using
        Alternatives.of(List(Schema.derived[DocumentOutlineResult], uriNotFound))
      ),
      "annotated_source" -> schema(using
        Alternatives.of(List(sourceView, Schema.derived[SourceWriteResult], uriNotFound))
      ),
      "symbol_source" -> schema(using Alternatives.of(List(sourceView, notFound, uriNotFound))),
      "source_around_position" -> schema(using Alternatives.of(List(sourceView, uriNotFound)))
    )

  private lazy val all: Map[String, ujson.Value] = schemaOf ++ PlanToolSchemas.schemaOf

  def attach(tools: List[Tool]): List[Tool] =
    tools.map(t =>
      all
        .get(t.name)
        .filter(_ => t.outputSchema.isEmpty)
        .fold(t)(s => t.copy(outputSchema = Some(s)))
    )
