package com.github.mercurievv.scalasemantic.wire.api

import io.circe.Encoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

/** A case-class relation group (`companion`, `constructors`, `copy`, `accessors`, `apply`,
  * `unapply`): the sites a plain type-reference search silently omits.
  */
final case class RelatedGroup(
    @description("relation kind") kind: String,
    @description("SemanticDB symbol of the related member") symbol: String,
    @description("total sites, before the `limit` cut") locationCount: Int,
    @description("uri:line:col, cut to `limit`") locations: List[String],
    @description("locations with source context (only when contextLines > 0)")
    hits: Option[List[UsageHitOut]]
) derives Encoder.AsObject,
      Schema

/** `find_usages`. Every section but `symbol`/`name`/`referenceCount` is omitted when empty or
  * excluded through `include`.
  */
final case class FindUsagesResult(
    @description("the queried SemanticDB symbol") symbol: String,
    @description("display name") name: String,
    @description("definition sites, uri:line:col") definitions: Option[List[String]],
    @description("total references before paging") referenceCount: Int,
    @description("this page of references, uri:line:col") references: Option[List[String]],
    @description("pass as `offset` for the next page; absent on the last page")
    nextOffset: Option[Int],
    @description("definitions with source context (contextLines > 0)")
    definitionsWithContext: Option[List[UsageHitOut]],
    @description("references with source context (contextLines > 0)")
    referencesWithContext: Option[List[UsageHitOut]],
    @description("case-class construction/copy/accessor sites") related: Option[List[RelatedGroup]]
) derives Encoder.AsObject,
      Schema

/** A reference hit as it goes on the wire: `uri:line:col` plus context lines when present. */
def usageHitOut(hit: com.github.mercurievv.scalasemantic.model.UsageHit): UsageHitOut =
  UsageHitOut(
    Locations.format(hit.location),
    Option.when(hit.context.nonEmpty)(hit.context.toList)
  )
