package com.github.mercurievv.scalasemantic.mcp

import sttp.tapir.Schema
import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType

// Deliberately a separate file with NO `import CallGraphSchemas.given`: the derivation below must
// not see the givens it feeds, or it summons a half-initialized (null) schema for the type it is
// deriving.

/** Schemas of the self-referencing result types.
  *
  * Why not `derives Schema`: that puts the type's own `Schema` given in its companion, i.e. in
  * implicit scope while tapir derives it. The `children: Option[List[Self]]` field then summons the
  * given that is still initializing, and a Scala 3 `lazy val` re-entered on the same thread blocks
  * forever.
  *
  * Instead the recursive field's schema is supplied explicitly as a reference ([[SchemaType.SRef]])
  * to the node's own name, and tapir derives the rest. [[OutputSchema]] emits that reference as a
  * `$ref` into `$defs`.
  */
private object RecursiveSchemaDerivation:

  private def selfRef[A](simpleName: String): Schema[A] =
    Schema(SchemaType.SRef[A](SName(s"com.github.mercurievv.scalasemantic.mcp.$simpleName")))

  val callNode: Schema[CallNodeOut] =
    given Schema[Option[List[CallNodeOut]]] =
      selfRef[CallNodeOut]("CallNodeOut").asIterable[List].asOption
    Schema.derived[CallNodeOut]

  val implicitTree: Schema[ImplicitTreeOut] =
    given Schema[Option[List[ImplicitTreeOut]]] =
      selfRef[ImplicitTreeOut]("ImplicitTreeOut").asIterable[List].asOption
    Schema.derived[ImplicitTreeOut]

object CallGraphSchemas:
  given Schema[CallNodeOut] = RecursiveSchemaDerivation.callNode
  given Schema[ImplicitTreeOut] = RecursiveSchemaDerivation.implicitTree
