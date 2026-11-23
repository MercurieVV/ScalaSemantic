package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.model.ContractResult
import com.github.mercurievv.scalasemantic.model.HoleContextResult
import com.github.mercurievv.scalasemantic.model.LiteralResult
import com.github.mercurievv.scalasemantic.model.ReferenceResult
import com.github.mercurievv.scalasemantic.wire.AnalysisCodecs.given
import com.github.mercurievv.scalasemantic.wire.api.*
import io.circe.Json
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.SchemaType

// Attaches the `outputSchema` of the tools whose bodies still build JSON by hand. The result
// types themselves live in `wire.api`; only the attachment needs `OutputSchema` and so stays here.

object Alternatives:
  /** An untagged union: the wire value is exactly one of the alternatives' own objects. */
  def of(alts: List[Schema[?]]): Schema[Any] =
    Schema(SchemaType.SCoproduct[Any](alts, None)(_ => None))

/** Attaches the `outputSchema` of the tools whose bodies still build JSON by hand. */
object SourceToolSchemas:

  private def schema[A](using s: Schema[A]): Json = OutputSchema.of[A]

  private val schemaOf: Map[String, Json] =
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

  private lazy val all: Map[String, Json] =
    schemaOf ++ PlanToolSchemas.schemaOf ++ AnalysisToolSchemas.schemaOf

  def attach(tools: List[Tool]): List[Tool] =
    tools.map(t =>
      all
        .get(t.name)
        .filter(_ => t.outputSchema.isEmpty)
        .fold(t)(s => t.copy(outputSchema = Some(s)))
    )

object PlanToolSchemas:

  private def schema[A](using s: Schema[A]): Json = OutputSchema.of[A]

  private[mcp] val schemaOf: Map[String, Json] =
    Map(
      "rename_plan" -> schema[RenamePlanResult],
      "batch_rename_plan" -> schema[BatchRenamePlanResult],
      "move_plan" -> schema[MovePlanResult],
      "extract_method_plan" -> schema(using
        Alternatives.of(List(Schema.derived[ExtractMethodPlanResult], Schema.derived[UriNotFound]))
      )
    )

object AnalysisToolSchemas:

  private def schema[A](using s: Schema[A]): Json = OutputSchema.of[A]

  private[mcp] val schemaOf: Map[String, Json] =
    Map(
      "structure" -> schema[StructureResult],
      "value_flow" -> schema(using
        Alternatives.of(List(Schema.derived[ValueFlowOut], Schema.derived[PositionMissing]))
      ),
      "smart_code_duplications" -> schema[DuplicationsResult],
      "source_ranges" -> schema[SourceRangesResult],
      "literal_tokens" -> schema[LiteralResult],
      "contract_clauses" -> schema[ContractResult],
      "resolve_references" -> schema[ReferenceResult],
      "hole_context" -> schema(using
        Alternatives.of(
          List(summon[Schema[HoleContextResult]], Schema.derived[NotFoundResult])
        )
      )
    )
