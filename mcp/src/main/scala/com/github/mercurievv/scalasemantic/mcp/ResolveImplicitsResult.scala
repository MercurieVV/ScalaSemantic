package com.github.mercurievv.scalasemantic.mcp

import io.circe.Encoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

final case class ImplicitCandidateOut(
    @description("SemanticDB symbol of the given/implicit definition") symbol: String,
    @description("its rendered type") `type`: String
) derives Encoder.AsObject,
      Schema

/** `resolve_implicits`: the given/implicit definitions that produce a wanted type. */
final case class ResolveImplicitsResult(
    @description("the wanted type's SemanticDB symbol") `type`: String,
    @description("the single applicable candidate, set only when exactly one applies")
    chosen: Option[String],
    @description("every candidate that produces the wanted type")
    candidates: List[ImplicitCandidateOut]
) derives Encoder.AsObject,
      Schema
