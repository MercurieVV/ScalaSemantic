package com.github.mercurievv.scalasemantic.mcp

import io.circe.Encoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

/** One `find_symbol` match. `layer`/`centrality`/`inCycle` appear only with `metrics: true`. */
final case class SymbolHit(
    @description("SemanticDB symbol string — pass it to the other tools") symbol: String,
    @description("display name") name: String,
    @description("SymbolInformation kind: CLASS, TRAIT, OBJECT, METHOD, …") kind: String,
    @description("structural layer, 0 = foundation (metrics only)") layer: Option[Int],
    @description("PageRank importance (metrics only)") centrality: Option[Double],
    @description("present and true when the type is in a dependency cycle (metrics only)")
    inCycle: Option[Boolean]
) derives Encoder.AsObject,
      Schema

final case class FindSymbolResult(
    @description("the query as given") query: String,
    @description("number of symbols returned") count: Int,
    @description("matches ranked exact > prefix > substring") symbols: List[SymbolHit]
) derives Encoder.AsObject,
      Schema
