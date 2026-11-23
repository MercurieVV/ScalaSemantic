package com.github.mercurievv.scalasemantic.wire.api

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

  // The name is the class's own, so moving the type between packages cannot desynchronise it from
  // the name tapir derives for the same type (a mismatch silently turns the `$ref` into a full copy).
  private def selfRef[A](using ct: scala.reflect.ClassTag[A]): Schema[A] =
    Schema(SchemaType.SRef[A](SName(ct.runtimeClass.getName)))

  val callNode: Schema[CallNodeOut] =
    given Schema[Option[List[CallNodeOut]]] =
      selfRef[CallNodeOut].asIterable[List].asOption
    Schema.derived[CallNodeOut]

  val implicitTree: Schema[ImplicitTreeOut] =
    given Schema[Option[List[ImplicitTreeOut]]] =
      selfRef[ImplicitTreeOut].asIterable[List].asOption
    Schema.derived[ImplicitTreeOut]

  val outlineEntry: Schema[OutlineEntryOut] =
    given Schema[Option[List[OutlineEntryOut]]] =
      selfRef[OutlineEntryOut].asIterable[List].asOption
    Schema.derived[OutlineEntryOut]

object OutlineSchemas:
  given Schema[OutlineEntryOut] = RecursiveSchemaDerivation.outlineEntry

object CallGraphSchemas:
  given Schema[CallNodeOut] = RecursiveSchemaDerivation.callNode
  given Schema[ImplicitTreeOut] = RecursiveSchemaDerivation.implicitTree
