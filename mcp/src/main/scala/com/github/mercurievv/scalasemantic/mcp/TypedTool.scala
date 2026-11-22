package com.github.mercurievv.scalasemantic.mcp

import io.circe.Encoder
import sttp.tapir.Schema

/** A [[Tool]] whose result is a typed value: circe encodes it to the wire JSON, tapir derives the
  * `outputSchema`. `None` fields are dropped from the wire (matches the hand-built `jobj`/`opt`
  * convention); empty collections are kept unless the type models them as `Option`.
  *
  * Bridges to the `ujson`-based `Tool` through a string round-trip until the protocol layer itself
  * moves to circe.
  */
object TypedTool:

  def apply[A](
      name: String,
      description: String,
      params: List[(String, String, String)],
      required: List[String]
  )(run: ujson.Value => A)(using enc: Encoder[A], schema: Schema[A]): Tool =
    val untyped = McpToolsSupport.tool(name, description, params, required)(_ => ujson.Null)
    untyped.copy(
      run = a => encode(run(a)),
      outputSchema = Some(OutputSchema.of[A])
    )

  def encode[A](a: A)(using enc: Encoder[A]): ujson.Value =
    ujson.read(enc(a).deepDropNullValues.noSpaces)
