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

final case class FindUsagesArgs(
    @description("SemanticDB symbol to search for") symbol: String,
    @description("max references to return (default 100)") limit: Option[Int],
    @description("reference offset for paging (default 0)") offset: Option[Int],
    @description("glob on document uri; `*` matches any chars (substring match)")
    pathFilter: Option[String],
    @description("sections to return: any of \"definitions\", \"references\" (default both)")
    include: Option[List[String]],
    @description("lines of surrounding source per hit (default 0 = no context, no extra file I/O)")
    contextLines: Option[Int],
    @description(
      "case-class relations to include: any of \"companion\", \"constructors\", \"copy\", " +
        "\"accessors\", \"apply\", \"unapply\" (default all; `[]` drops the section)"
    )
    related: Option[List[String]]
) derives Decoder,
      Schema

final case class MethodSignatureArgs(
    @description(
      "method/val/var symbol, OR a file path — a file with exactly one method returns its " +
        "signature; several methods return `{ candidates }` to re-call with one"
    )
    symbol: String,
    @description("include structured parameter breakdown (default false)")
    detailed: Option[Boolean],
    @description(
      "defining file's uri (path relative to project root); with `source`, overlays it"
    )
    uri: Option[String],
    @description("current full text of the file at `uri`; enables the live PC overlay")
    source: Option[String]
) derives Decoder,
      Schema

final case class ClassHierarchyArgs(
    @description("class or trait symbol, or a file path") symbol: String,
    @description("expand related types to {symbol,name,kind} (default false)")
    detailed: Option[Boolean],
    @description("glob on a related type's definition uri; `*` matches any chars")
    pathFilter: Option[String],
    @description(
      "sections to return: any of \"parents\", \"linearization\", \"knownSubtypes\" (default all)"
    )
    include: Option[List[String]],
    @description(
      "badge the queried type with its structural layer/centrality/inCycle (default false)"
    )
    metrics: Option[Boolean]
) derives Decoder,
      Schema

final case class FindOverloadsArgs(
    @description("any one overload's symbol, or a file path") symbol: String
) derives Decoder,
      Schema

final case class MembersArgs(
    @description("class or trait symbol, or a file path") symbol: String,
    @description("include kinds and declaring symbols (default false)") detailed: Option[Boolean],
    @description("glob on a member's definition uri; `*` matches any chars")
    pathFilter: Option[String],
    @description("sections to return: any of \"declared\", \"inherited\" (default both)")
    include: Option[List[String]]
) derives Decoder,
      Schema

final case class ResolveImplicitsArgs(
    @description("the wanted type's symbol, e.g. pkg/Show#") `type`: String
) derives Decoder,
      Schema

final case class TraceImplicitChainArgs(
    @description("the wanted type's symbol") `type`: String,
    @description(
      "optional concrete wanted type, e.g. `Show[List[Int]]`; returns one resolved tree when unambiguous"
    )
    appliedType: Option[String]
) derives Decoder,
      Schema

final case class CallPathArgs(
    @description("caller method symbol") from: String,
    @description("callee method symbol") to: String,
    @description("include call-site edge locations (default false)") detailed: Option[Boolean]
) derives Decoder,
      Schema

final case class CallHierarchyArgs(
    @description("method symbol") symbol: String,
    @description(
      "\"callers\" (incoming — who calls this) or \"callees\" (outgoing — what this calls); default \"callees\""
    )
    direction: Option[String],
    @description("how many levels to expand (default 3)") depth: Option[Int]
) derives Decoder,
      Schema

final case class TypeAtPositionArgs(
    @description("document uri as it appears in SemanticDB (path relative to project root)")
    uri: String,
    @description("0-based line") line: Int,
    @description("0-based column") character: Int,
    @description("current full text of the file at `uri`; enables the live PC overlay")
    source: Option[String]
) derives Decoder,
      Schema

final case class SearchTextArgs(
    @description("text (or regex, with `regex: true`) to search for") query: String,
    @description("treat `query` as a regex instead of a literal string (default false)")
    regex: Option[Boolean],
    @description("match case-sensitively (default false)") caseSensitive: Option[Boolean],
    @description("glob on document uri; `*` matches any chars (substring match)")
    pathFilter: Option[String],
    @description("max hits to return across all files (default 200)") limit: Option[Int]
) derives Decoder,
      Schema
