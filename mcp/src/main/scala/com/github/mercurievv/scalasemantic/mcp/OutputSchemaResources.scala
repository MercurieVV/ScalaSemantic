package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.wire.api.*
import io.circe.Json

/** Output schemas of the typed tools, served on demand instead of riding along in every
  * `tools/list`: schemas cost tokens per session, and only code that parses results (programmatic
  * tool calling, code-mode) needs them.
  *
  * Two channels, same data: MCP resources (`scalasemantic://output-schema/{tool}` and
  * `scalasemantic://output-schema/all`) and the `output_schema` tool for clients without resource
  * support. Advertising `outputSchema` in `tools/list` and sending `structuredContent` with each
  * result is opt-in (`SCALASEMANTIC_STRUCTURED=1`), because `structuredContent` duplicates the text
  * block on every call.
  */
object OutputSchemaResources:

  val UriPrefix: String = "scalasemantic://output-schema/"
  val AllUri: String = UriPrefix + "all"
  private val MimeType = "application/json"

  /** Opt-in switch: env `SCALASEMANTIC_STRUCTURED` or system property `scalasemantic.structured`.
    */
  def structuredDefault: Boolean =
    sys.env
      .get("SCALASEMANTIC_STRUCTURED")
      .orElse(sys.props.get("scalasemantic.structured"))
      .exists(v => v == "1" || v.equalsIgnoreCase("true"))

  /** Typed tools only, sorted by name: `(tool name, output schema)`. */
  def schemas(tools: List[Tool]): List[(String, Json)] =
    tools.flatMap(t => t.outputSchema.map(t.name -> _)).sortBy(_._1)

  def list: Json =
    J.obj(
      "resources" -> J.arr(
        J.obj(
          "uri" -> AllUri,
          "name" -> "output-schema-all",
          "description" -> "Output JSON Schemas of every typed tool, keyed by tool name",
          "mimeType" -> MimeType
        )
      )
    )

  def templates: Json =
    J.obj(
      "resourceTemplates" -> J.arr(
        J.obj(
          "uriTemplate" -> (UriPrefix + "{tool}"),
          "name" -> "output-schema",
          "description" -> "Output JSON Schema of one typed tool (e.g. find_usages)",
          "mimeType" -> MimeType
        )
      )
    )

  /** `resources/read` result, or the JSON-RPC error message (code -32002, resource not found). */
  def read(uri: String, tools: List[Tool]): Either[String, Json] =
    text(uri, tools).map: body =>
      J.obj(
        "contents" -> J.arr(J.obj("uri" -> uri, "mimeType" -> MimeType, "text" -> body))
      )

  private def text(uri: String, tools: List[Tool]): Either[String, String] =
    if uri == AllUri then Right(J.write(J.objOf(schemas(tools))))
    else if uri.startsWith(UriPrefix) then
      forTool(uri.stripPrefix(UriPrefix), tools).map(J.write(_))
    else Left(s"Unknown resource: $uri")

  private def forTool(name: String, tools: List[Tool]): Either[String, Json] =
    schemas(tools)
      .collectFirst { case (n, s) if n == name => s }
      .toRight(
        s"No output schema for tool '$name'; typed tools: ${schemas(tools).map(_._1).mkString(", ")}"
      )

  /** The same data as a tool, for clients that do not support MCP resources. */
  def tool(all: List[Tool]): Tool =
    TypedTool.withArgs[OutputSchemaArgs](
      "output_schema",
      "Output JSON Schema of typed tools; pass `tool` for one, omit for all (keyed by name). " +
        "Only needed to write code that parses results."
    ) { a =>
      a.tool match
        case None       => J.objOf(schemas(all))
        case Some(name) => forTool(name, all).fold(McpToolsSupport.error, identity)
    }
