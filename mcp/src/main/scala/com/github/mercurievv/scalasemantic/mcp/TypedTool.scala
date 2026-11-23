package com.github.mercurievv.scalasemantic.mcp

import io.circe.CursorOp
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.Json
import sttp.tapir.Schema

import JOps.*

/** A [[Tool]] whose arguments and result are typed values: circe decodes the request and encodes
  * the reply, tapir derives the `inputSchema` and the `outputSchema` from the same two types.
  * `None` fields are dropped from the wire (matches the hand-built `jobj`/`opt` convention); empty
  * collections are kept unless the type models them as `Option`.
  */
object TypedTool:

  /** Output-typed tool; arguments are still described by hand and read with the `arg*` helpers. */
  def apply[A](
      name: String,
      description: String,
      params: List[(String, String, String)],
      required: List[String]
  )(run: Json => A)(using enc: Encoder[A], schema: Schema[A]): Tool =
    val untyped = McpToolsSupport.tool(name, description, params, required)(_ => Json.Null)
    untyped.copy(
      run = a => encode(run(a)),
      outputSchema = Some(OutputSchema.of[A])
    )

  /** Input- and output-typed tool. A field is `required` in the derived `inputSchema` unless it is
    * an `Option`; a missing or mistyped argument fails with one message naming the field. Arguments
    * the schema does not declare are ignored (and logged, see [[warnUnknownArgs]]).
    */
  def of[In, Out](name: String, description: String)(run: In => Out)(using
      dec: Decoder[In],
      inSchema: Schema[In],
      enc: Encoder[Out],
      outSchema: Schema[Out]
  ): Tool =
    withArgs[In](name, description)(in => encode(run(in)))
      .copy(outputSchema = Some(OutputSchema.of[Out]))

  /** Input-typed tool whose result is still built by hand (its `outputSchema`, if any, is attached
    * separately — see `SourceToolSchemas`).
    */
  def withArgs[In](name: String, description: String)(run: In => Json)(using
      dec: Decoder[In],
      inSchema: Schema[In]
  ): Tool =
    val inputSchema = OutputSchema.emit(inSchema)
    val declared: Set[String] =
      inputSchema.get("properties").flatMap(_.objOpt).map(_.keySet.toSet).getOrElse(Set.empty)
    val untyped = McpToolsSupport.tool(name, description, Nil, Nil)(_ => Json.Null)
    untyped.copy(
      inputSchema = inputSchema,
      run = args =>
        warnUnknownArgs(name, args, declared)
        decodeArgs[In](name, args).fold(McpToolsSupport.error, run)
    )

  def encode[A](a: A)(using enc: Encoder[A]): Json = enc(a).deepDropNullValues

  /** Arguments every tool accepts without declaring them (see [[ResultShaping]]). */
  private val SharedArgs: Set[String] = Set("fields", "countOnly", "limit")

  /** `SCALASEMANTIC_WARN_UNKNOWN_ARGS=1` / `-Dscalasemantic.warnUnknownArgs=true`: one log line per
    * call naming the arguments a typed tool ignored. Off by default; never an error to the client.
    */
  private def warnUnknownArgs(tool: String, args: Json, declared: Set[String]): Unit =
    val on =
      sys.env.get("SCALASEMANTIC_WARN_UNKNOWN_ARGS").contains("1") ||
        sys.props.get("scalasemantic.warnUnknownArgs").contains("true")
    if on then
      val unknown =
        args.asObject.toList.flatMap(_.keys.toList).filterNot(k => declared(k) || SharedArgs(k))
      if unknown.nonEmpty then
        Mcp.log.get()(
          s"warning: $tool ignored unknown argument(s): ${unknown.sorted.mkString(", ")}"
        )

  private def decodeArgs[In](tool: String, args: Json)(using
      dec: Decoder[In]
  ): Either[String, In] =
    dec.decodeJson(args).left.map(f => s"$tool: invalid arguments: ${describe(f)}")

  private def describe(f: DecodingFailure): String =
    val path = CursorOp.opsToPath(f.history)
    val where = if path.isEmpty then "" else s"`${path.stripPrefix(".")}`: "
    where + f.message
end TypedTool
