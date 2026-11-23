package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.model.ContractResult
import com.github.mercurievv.scalasemantic.model.HoleContextResult
import com.github.mercurievv.scalasemantic.model.LiteralResult
import com.github.mercurievv.scalasemantic.model.ReferenceResult
import com.github.mercurievv.scalasemantic.wire.AnalysisCodecs.given
import io.circe.Json
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

// Wire shapes of the analysis tools. Schema-only, like `SourceToolResults`: the tools keep their
// own serialisation and `AnalysisToolSchemaSuite` validates real answers against these types.
//
// The tools that return an analysis-module model as it is (`literal_tokens`, `contract_clauses`,
// `resolve_references`, `hole_context`) take its schema from the `wire` module, the same derivation
// that encodes the result — nothing is mirrored here.

/** `structure`: one module of the rollup. */
final case class StructureModule(
    @description("module name") module: String,
    @description("number of types") types: Int,
    @description("dependency-chain depth, 0 = foundation") layer: Int,
    @description("afferent coupling (fan-in)") ca: Int,
    @description("efferent coupling (fan-out)") ce: Int,
    @description("Ce/(Ca+Ce)") instability: Double,
    @description("true when in a module cycle") inCycle: Option[Boolean]
) derives Schema

final case class StructureEdge(
    @description("depending symbol") from: String,
    @description("depended-on symbol") to: String,
    @description("edge dimensions: extends, typeRef, memberType, call, implicit") dimensions: List[
      String
    ],
    @description("number of dependency observations collapsed into the edge") weight: Int
) derives Schema

final case class DimensionMetrics(ca: Int, ce: Int, instability: Double) derives Schema

final case class StructureSymbol(
    @description("SemanticDB symbol string") symbol: String,
    @description("display name") name: String,
    @description("owning module") module: String,
    @description("dependency-chain depth, 0 = foundation") layer: Int,
    @description("afferent coupling (fan-in)") ca: Int,
    @description("efferent coupling (fan-out)") ce: Int,
    @description("Ce/(Ca+Ce)") instability: Double,
    @description("PageRank importance") centrality: Double,
    @description("true when in a dependency cycle") inCycle: Option[Boolean],
    @description("metrics per edge dimension (with detailed: true)")
    perDimension: Option[Map[String, DimensionMetrics]]
) derives Schema

final case class StructureCycle(
    @description("edge dimension the cycle lives in") dimension: String,
    @description("symbols in the cycle") members: List[String]
) derives Schema

final case class StructureResult(
    @description("dimension the metrics were taken over") dimension: String,
    @description("ranking key") sort: String,
    @description("module rollup") modules: List[StructureModule],
    @description("symbol-level edges among the returned symbols") dependencies: List[
      StructureEdge
    ],
    @description("`from->to (weight)`, with CYCLE for mutual dependencies (absent when none)")
    moduleEdges: Option[List[String]],
    @description("ranked symbols") symbols: List[StructureSymbol],
    @description("dependency cycles (absent when none)") cycles: Option[List[StructureCycle]],
    @description("mermaid `graph TD` of moduleEdges (with graph: true)") mermaid: Option[String]
) derives Schema

/** `value_flow`. */
final case class FlowNode(
    @description("SemanticDB symbol string") symbol: String,
    @description("display name") name: String,
    @description("rendered type") `type`: Option[String],
    @description("`uri:line:col` of the binding") at: Option[String],
    @description("hops from the root") depth: Int,
    @description("method the binding lives in") enclosingMethod: Option[String],
    @description("binding kind") kind: Option[String]
) derives Schema

final case class FlowEdge(
    @description("source symbol") from: String,
    @description("target symbol") to: String,
    @description("how the value moves") relation: String,
    @description("`uri:line:col` of the hop") at: String,
    @description("parameter the value enters") paramName: Option[String],
    @description("sibling parameters of the same call") coParameters: Option[List[String]]
) derives Schema

final case class FlowTerminal(
    @description("symbol where the flow ended") symbol: String,
    @description("how the value leaves: return, sink, widening, …") classification: String,
    @description("`uri:line:col`") at: Option[String]
) derives Schema

final case class ValueFlowOut(
    @description("the traced binding") root: FlowNode,
    @description("every binding reached, including the root") nodes: List[FlowNode],
    @description("hops between bindings (absent when none)") edges: Option[List[FlowEdge]],
    @description("flows stopped at a type widening (absent when none)")
    stoppedAt: Option[List[FlowTerminal]],
    @description("flows cut off by maxDepth (absent when none)")
    truncatedAt: Option[List[FlowTerminal]]
) derives Schema

/** `smart_code_duplications`. */
final case class DuplicateOccurrence(
    @description("`uri:line:col`") location: String,
    @description("method the clone sits in") enclosingMethod: Option[String]
) derives Schema

final case class DuplicateGroup(
    @description("number of clones") occurrencesCount: Int,
    @description("AST size of each clone") astNodeCount: Int,
    @description("where the clones are") occurrences: List[DuplicateOccurrence]
) derives Schema

final case class DuplicateSnippet(
    @description("`uri:line:col`") location: String,
    @description("gutter-numbered source lines") snippet: String
) derives Schema

final case class DuplicateSourceGroup(
    @description("AST size of each clone") astNodeCount: Int,
    @description("clones with their source") occurrences: List[DuplicateSnippet]
) derives Schema

final case class DuplicationsResult(
    @description("number of clone groups") groupsCount: Int,
    @description("clone groups") groups: List[DuplicateGroup],
    @description("the same groups with source (with showSource: true)")
    groupsSource: Option[List[DuplicateSourceGroup]]
) derives Schema

/** `source_ranges`: one answer per requested range. */
sealed trait RangeAnswer derives Schema

/** A resolved slice. */
final case class RangeSlice(
    @description("document uri") uri: String,
    @description("always enriched") format: String,
    @description("number of compiler notes in `source`") annotationCount: Int,
    @description("how to read `source`") legend: String,
    @description("the gutter-numbered, annotated lines") source: String,
    @description("the target as requested") target: String,
    @description("`start-end` as requested") requestedRange: String,
    @description("always true") found: Boolean,
    @description("true when answered by the presentation compiler") pcFallback: Option[Boolean],
    @description("why the answer may have gaps (with pcFallback)") pcFallbackHint: Option[String]
) extends RangeAnswer derives Schema

/** A target or range that did not resolve. */
final case class RangeMissing(
    @description("the target as requested") target: String,
    @description("`start-end` as requested") requestedRange: Option[String],
    @description("always false") found: Boolean,
    @description("why it did not resolve") error: String
) extends RangeAnswer derives Schema

final case class SourceRangesResult(
    @description("the query as given") query: String,
    @description("one entry per requested range, in order") results: List[RangeAnswer]
) derives Schema

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
