package com.github.mercurievv.scalasemantic.mcp

import io.circe.Decoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

// Typed tool arguments (ADR 0006, stage B). One `derives` gives the decoder that reads the request
// and the schema that becomes the tool's `inputSchema`. A field is required exactly when it is not
// an `Option`; defaults live in the tool body, and the description says what they are.

final case class FindSymbolArgs(
    @description("simple or partial name to search for") query: String,
    @description("max results (default 50)") limit: Option[Int],
    @description("match the display name exactly, case-insensitive (default false)")
    exact: Option[Boolean],
    @description("keep only this SymbolInformation kind, e.g. TRAIT, CLASS, METHOD")
    kind: Option[String],
    @description("glob on the symbol's definition uri; `*` matches any chars")
    pathFilter: Option[String],
    @description(
      "badge each result type with its structural layer/centrality/inCycle (default false)"
    )
    metrics: Option[Boolean]
) derives Decoder,
      Schema
