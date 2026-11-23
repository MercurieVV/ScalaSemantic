package com.github.mercurievv.scalasemantic.wire.api

import com.github.mercurievv.scalasemantic.model.MemberInfo
import io.circe.Encoder
import io.circe.Json
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.SchemaType

/** A bare display name, or the full object when the caller asked for detail. Encoded untagged (a
  * string, or `A`'s own object); every entry of one response is the same kind.
  */
enum NameOr[+A]:
  case Name(display: String)
  case Full(value: A)

object NameOr:
  given [A](using enc: Encoder[A]): Encoder[NameOr[A]] = Encoder.instance:
    case Name(display) => Json.fromString(display)
    case Full(value)   => enc(value)

  given [A](using full: Schema[A]): Schema[NameOr[A]] =
    Schema(
      SchemaType.SCoproduct[NameOr[A]](List(summon[Schema[String]], full), None)(_ => None)
    )

/** A member. `kind` and `symbol` are present only in the detailed view; `from` names the type that
  * declares it.
  */
final case class MemberOut(
    @description("member display name") name: String,
    @description("SymbolInformation kind (detailed only)") kind: Option[String],
    @description("SemanticDB symbol (detailed only)") symbol: Option[String],
    @description("display name of the declaring type") from: String
) derives Encoder.AsObject,
      Schema

object MemberOut:
  def detailed(mi: MemberInfo): MemberOut =
    MemberOut(mi.displayName, Some(mi.kind.value), Some(mi.symbol), mi.declaredIn.displayName)

  def brief(mi: MemberInfo): MemberOut =
    MemberOut(mi.displayName, None, None, mi.declaredIn.displayName)

/** `members`: what a type declares versus inherits. `declared` entries are bare names unless
  * `detailed`; `inherited` entries are always objects (`{name, from}`, or the full member).
  */
final case class MembersOut(
    @description("the queried SemanticDB symbol") symbol: String,
    @description("display name") name: String,
    @description("members the type declares") declared: Option[List[NameOr[MemberOut]]],
    @description("members inherited through the linearization") inherited: Option[List[MemberOut]]
) derives Encoder.AsObject,
      Schema
