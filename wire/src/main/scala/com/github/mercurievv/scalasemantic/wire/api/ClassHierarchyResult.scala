package com.github.mercurievv.scalasemantic.wire.api

import com.github.mercurievv.scalasemantic.model.SymbolRef
import io.circe.Encoder
import io.circe.Json
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.SchemaType

/** A related type: just its display name, or `{symbol, name, kind}` when `detailed` was asked for.
  * Encoded untagged (a bare string, or an object); every entry of one response is the same kind.
  */
enum RefOut:
  case Name(display: String)
  case Detailed(ref: DetailedRef)

final case class DetailedRef(
    @description("SemanticDB symbol string") symbol: String,
    @description("display name") name: String,
    @description("SymbolInformation kind") kind: String
) derives Encoder.AsObject,
      Schema

object RefOut:
  def from(r: SymbolRef, detailed: Boolean): RefOut =
    if detailed then Detailed(DetailedRef(r.symbol, r.displayName, r.kind.value))
    else Name(r.displayName)

  given Encoder[RefOut] = Encoder.instance:
    case Name(display) => Json.fromString(display)
    case Detailed(ref) => Encoder[DetailedRef].apply(ref)

  given Schema[RefOut] =
    Schema(
      SchemaType.SCoproduct[RefOut](
        List(summon[Schema[String]], Schema.derived[DetailedRef]),
        None
      )(_ => None)
    )

/** `class_hierarchy`. Sections are omitted when empty or excluded through `include`; the metric
  * fields appear only with `metrics: true`.
  */
final case class ClassHierarchyResult(
    @description("the queried SemanticDB symbol") symbol: String,
    @description("display name") name: String,
    @description("structural layer, 0 = foundation (metrics only)") layer: Option[Int],
    @description("PageRank importance (metrics only)") centrality: Option[Double],
    @description("present and true when the type is in a dependency cycle (metrics only)")
    inCycle: Option[Boolean],
    @description("direct supertypes") parents: Option[List[RefOut]],
    @description("transitive linearization") linearization: Option[List[RefOut]],
    @description("known subtypes/implementers anywhere in the project")
    knownSubtypes: Option[List[RefOut]]
) derives Encoder.AsObject,
      Schema

/** `find_overloads`. */
final case class FindOverloadsResult(
    @description("method name shared by the overloads") name: String,
    @description("rendered signatures of every overload") overloads: List[String],
    @description("same-named methods inherited from parents, each suffixed `(from <Parent>)`")
    inheritedOverloads: Option[List[String]]
) derives Encoder.AsObject,
      Schema
