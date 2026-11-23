package com.github.mercurievv.scalasemantic.model

/** Wire model for the typed-hole query (spec 2026-09-21-typed-hole-context-design §1/§2): what a
  * position expects and what in scope can satisfy it. [[HoleContextResult.from]] is a pure
  * conversion of the `pc` module's `HoleContext.Result` — it never touches the compiler itself.
  */
final case class HoleExpected(`type`: Option[String], confidence: String, why: String)
final case class HoleBinding(name: String, `type`: String, kind: String, from: String)
final case class HoleProducer(name: String, signature: String, from: String, `match`: String)
final case class HoleGiven(name: String, `type`: String, from: String)
final case class HoleType(
    symbol: String,
    source: String,
    signature: String,
    excerpt: Option[String]
)
final case class HoleOmitted(bindings: Int, producers: Int, givens: Int, types: Int)
final case class HoleMarker(kind: String, text: String)
final case class HoleContextResult(
    found: Boolean,
    why: Option[String],
    hole: Option[HoleMarker],
    expected: Option[HoleExpected],
    bindings: List[HoleBinding],
    producers: List[HoleProducer],
    givens: List[HoleGiven],
    types: List[HoleType],
    omitted: HoleOmitted
)
