package com.github.mercurievv.scalasemantic.wire.api

import io.circe.Encoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

final case class ParameterOut(
    @description("parameter name") name: String,
    @description("rendered parameter type") `type`: String,
    @description("present and true for an implicit/using parameter") `implicit`: Option[Boolean]
) derives Encoder.AsObject,
      Schema

final case class ParameterListOut(
    @description("true for an implicit/using parameter list") `implicit`: Boolean,
    params: List[ParameterOut]
) derives Encoder.AsObject,
      Schema

/** `method_signature`. Without `detailed` only `symbol` and `signature` are present. */
final case class MethodSignatureResult(
    @description("the resolved SemanticDB symbol") symbol: String,
    @description("rendered signature, e.g. `def f[A](x: A): A` or `val x: T`") signature: String,
    @description("type parameters (detailed only)") typeParameters: Option[List[String]],
    @description("parameter lists (detailed only)") parameterLists: Option[List[ParameterListOut]],
    @description("rendered return type (detailed only)") returnType: Option[String]
) derives Encoder.AsObject,
      Schema
