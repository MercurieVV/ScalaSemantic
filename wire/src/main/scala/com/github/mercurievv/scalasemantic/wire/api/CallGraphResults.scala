package com.github.mercurievv.scalasemantic.wire.api

import com.github.mercurievv.scalasemantic.model.CallHierarchyNode
import com.github.mercurievv.scalasemantic.model.ImplicitTree
import io.circe.Encoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

import CallGraphSchemas.given

/** `call_path`: shortest call path between two methods. */
final case class CallPathResult(
    @description("caller method display name") from: String,
    @description("callee method display name") to: String,
    @description("whether `to` is reachable from `from`") reachable: Boolean,
    @description("display names along the path (reachable only)") path: Option[List[String]],
    @description("call-site edges `from->to@uri:line:col` (only with `detailed`)")
    edges: Option[List[String]]
) derives Encoder.AsObject,
      Schema

/** One method in a call hierarchy; a recursive call appears as a leaf.
  *
  * Recursive: no `derives Schema` — see [[CallGraphSchemas]].
  */
final case class CallNodeOut(
    @description("SemanticDB symbol of the method") symbol: String,
    @description("display name") name: String,
    @description("call-site location uri:line:col (absent on the root)") at: Option[String],
    @description("callers/callees one level down; absent on leaves")
    children: Option[List[CallNodeOut]]
) derives Encoder.AsObject

object CallNodeOut:
  def from(n: CallHierarchyNode): CallNodeOut =
    CallNodeOut(
      n.method.symbol,
      n.method.displayName,
      n.at.map(Locations.format),
      Option.when(n.children.nonEmpty)(n.children.toList.map(from))
    )

/** `method_call_hierarchy`: callers (incoming) or callees (outgoing) as a tree. */
final case class CallHierarchyResult(
    @description("queried method symbol") symbol: String,
    @description("display name") name: String,
    @description("`callers` or `callees`") direction: String,
    @description("levels expanded") depth: Int,
    @description("the call tree, rooted at the queried method") hierarchy: CallNodeOut
) derives Encoder.AsObject,
      Schema

final case class ImplicitStepOut(
    @description("display name of the given") `given`: String,
    @description("its rendered type") `type`: String,
    @description("givens it depends on") dependsOn: Option[List[String]]
) derives Encoder.AsObject,
      Schema

/** A resolution tree: the chosen given for a wanted type and what each pulls in.
  *
  * Recursive: no `derives Schema` — see [[CallGraphSchemas]].
  */
final case class ImplicitTreeOut(
    @description("the wanted type") `type`: String,
    @description("symbol of the chosen given, when exactly one applies") chosen: Option[String],
    @description("present and true when several candidates apply") ambiguous: Option[Boolean],
    @description("present and true when resolution cycles back on itself") cycle: Option[Boolean],
    @description("candidate givens") candidates: Option[List[ImplicitCandidateOut]],
    @description("implicit dependencies of the chosen given")
    children: Option[List[ImplicitTreeOut]]
) derives Encoder.AsObject

object ImplicitTreeOut:
  def from(t: ImplicitTree): ImplicitTreeOut =
    ImplicitTreeOut(
      `type` = t.targetType,
      chosen = t.chosen.map(_.symbol),
      ambiguous = Option.when(t.ambiguous)(true),
      cycle = Option.when(t.cycle)(true),
      candidates = Option.when(t.candidates.nonEmpty)(
        t.candidates.toList.map(c => ImplicitCandidateOut(c.target.symbol, c.tpe))
      ),
      children = Option.when(t.children.nonEmpty)(t.children.toList.map(from))
    )

/** `trace_implicit_chain`. */
final case class ImplicitChainResult(
    @description("the wanted type symbol") `type`: String,
    @description("the concrete wanted type, echoed when given") appliedType: Option[String],
    @description("givens that produce the type, with their dependencies")
    steps: List[ImplicitStepOut],
    @description("one resolved tree, when `appliedType` makes the choice unambiguous")
    resolved: Option[ImplicitTreeOut]
) derives Encoder.AsObject,
      Schema
