package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.model.Clause
import com.github.mercurievv.scalasemantic.model.Contract
import com.github.mercurievv.scalasemantic.model.ContractResult
import com.github.mercurievv.scalasemantic.model.Enclosing
import com.github.mercurievv.scalasemantic.model.FileContracts
import com.github.mercurievv.scalasemantic.model.FileLiterals
import com.github.mercurievv.scalasemantic.model.FileReferences
import com.github.mercurievv.scalasemantic.model.HoleBinding
import com.github.mercurievv.scalasemantic.model.HoleContextResult
import com.github.mercurievv.scalasemantic.model.HoleExpected
import com.github.mercurievv.scalasemantic.model.HoleGiven
import com.github.mercurievv.scalasemantic.model.HoleMarker
import com.github.mercurievv.scalasemantic.model.HoleOmitted
import com.github.mercurievv.scalasemantic.model.HoleProducer
import com.github.mercurievv.scalasemantic.model.HoleType
import com.github.mercurievv.scalasemantic.model.Literal
import com.github.mercurievv.scalasemantic.model.LiteralResult
import com.github.mercurievv.scalasemantic.model.Reference
import com.github.mercurievv.scalasemantic.model.ReferenceResult
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

// Wire shapes of the analysis tools. Schema-only, like `SourceToolResults`: the tools keep their
// own serialisation and `AnalysisToolSchemaSuite` validates real answers against these types.
//
// Two serialisations meet here. The hand-built tools (`structure`, `value_flow`, …) drop absent
// optionals. The tools that write an analysis-module model (`literal_tokens`, `contract_clauses`,
// `hole_context`, `resolve_references`) write an absent `Option` as an explicit `null`, so their
// schemas go through [[NullableOptionals]].

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

/** Makes every optional property of a schema also accept `null` — for the tools that write an
  * absent `Option` as an explicit `null` instead of dropping it.
  */
object NullableOptionals:

  def apply(schema: ujson.Value): ujson.Value = schema match
    case o: ujson.Obj =>
      val required: Set[String] = o.value.get("required") match
        case Some(a: ujson.Arr) => a.arr.toList.flatMap(_.strOpt).toSet
        case _                  => Set.empty
      ujson.Obj.from(o.value.toList.map {
        case ("properties", p: ujson.Obj) =>
          "properties" -> ujson.Obj.from(
            p.value.toList.map((k, v) => k -> (if required(k) then apply(v) else orNull(apply(v))))
          )
        case (k, v) => k -> apply(v)
      })
    case a: ujson.Arr => ujson.Arr.from(a.value.toList.map(apply))
    case other        => other

  private val Null: ujson.Value = ujson.Obj("type" -> "null")

  private def orNull(v: ujson.Value): ujson.Value = v match
    case o: ujson.Obj if o.value.contains("oneOf") =>
      ujson.Obj.from(o.value.toList.map {
        case ("oneOf", a: ujson.Arr) => "oneOf" -> ujson.Arr.from(a.value.toList ++ List(Null))
        case other                   => other
      })
    case o: ujson.Obj if o.value.get("type").exists(_.strOpt.isDefined) =>
      ujson.Obj.from(o.value.toList.map {
        case ("type", t) => "type" -> ujson.Arr(t, ujson.Str("null"))
        case other       => other
      })
    case other => ujson.Obj("oneOf" -> ujson.Arr(other, Null))

/** Schemas of the analysis-module models. Those types carry no tapir instances, so nested types are
  * derived automatically here (and only here).
  */
private object ModelSchemas:
  // Bottom-up semi-automatic derivation: tapir needs each nested type's schema in scope.
  given Schema[Enclosing] = Schema.derived
  given Schema[Literal] = Schema.derived
  given Schema[FileLiterals] = Schema.derived
  given Schema[Clause] = Schema.derived
  given Schema[Contract] = Schema.derived
  given Schema[FileContracts] = Schema.derived
  given Schema[Reference] = Schema.derived
  given Schema[FileReferences] = Schema.derived
  given Schema[HoleExpected] = Schema.derived
  given Schema[HoleBinding] = Schema.derived
  given Schema[HoleProducer] = Schema.derived
  given Schema[HoleGiven] = Schema.derived
  given Schema[HoleType] = Schema.derived
  given Schema[HoleOmitted] = Schema.derived
  given Schema[HoleMarker] = Schema.derived

  val literals: Schema[LiteralResult] = Schema.derived
  val contracts: Schema[ContractResult] = Schema.derived
  val references: Schema[ReferenceResult] = Schema.derived
  val hole: Schema[HoleContextResult] = Schema.derived

object AnalysisToolSchemas:

  private def schema[A](using s: Schema[A]): ujson.Value = OutputSchema.of[A]

  private def nullable[A](using s: Schema[A]): ujson.Value = NullableOptionals(OutputSchema.of[A])

  private[mcp] val schemaOf: Map[String, ujson.Value] =
    Map(
      "structure" -> schema[StructureResult],
      "value_flow" -> schema(using
        Alternatives.of(List(Schema.derived[ValueFlowOut], Schema.derived[PositionMissing]))
      ),
      "smart_code_duplications" -> schema[DuplicationsResult],
      "source_ranges" -> schema[SourceRangesResult],
      "literal_tokens" -> nullable(using ModelSchemas.literals),
      "contract_clauses" -> nullable(using ModelSchemas.contracts),
      "resolve_references" -> nullable(using ModelSchemas.references),
      "hole_context" -> NullableOptionals(
        OutputSchema.of(using
          Alternatives.of(
            List(ModelSchemas.hole, Schema.derived[NotFoundResult])
          )
        )
      )
    )
