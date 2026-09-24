package com.github.mercurievv.scalasemantic.model

import upickle.default.*

/** Wire model for the typed-hole query (spec 2026-09-21-typed-hole-context-design §1/§2): what a
  * position expects and what in scope can satisfy it. [[HoleContextResult.from]] is a pure
  * conversion of the `pc` module's `HoleContext.Result` — it never touches the compiler itself.
  */
final case class HoleExpected(`type`: Option[String], confidence: String, why: String)
    derives ReadWriter
final case class HoleBinding(name: String, `type`: String, kind: String, from: String)
    derives ReadWriter
final case class HoleProducer(name: String, signature: String, from: String, `match`: String)
    derives ReadWriter
final case class HoleGiven(name: String, `type`: String, from: String) derives ReadWriter
final case class HoleType(
    symbol: String,
    source: String,
    signature: String,
    excerpt: Option[String]
) derives ReadWriter
final case class HoleOmitted(bindings: Int, producers: Int, givens: Int, types: Int)
    derives ReadWriter
final case class HoleMarker(kind: String, text: String) derives ReadWriter
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
) derives ReadWriter

object HoleContextResult:
  /** Pure conversion from the pc result; `excerptOf` supplies a project type's definition excerpt
    * (`None` → classpath, so the type is reported with its signature alone).
    */
  def from(
      r: com.github.mercurievv.scalasemantic.pc.HoleContext.Result,
      excerptOf: String => Option[String]
  ): HoleContextResult =
    HoleContextResult(
      found = r.found,
      why = r.why,
      hole = r.hole.map(h => HoleMarker(h.kind, h.text)),
      expected =
        r.expected.map(e => HoleExpected(e.tpe, e.confidence.toString.toLowerCase.nn, e.why)),
      bindings = r.bindings.map(b => HoleBinding(b.name, b.tpe, b.kind, b.from)),
      producers = r.producers.map(p => HoleProducer(p.name, p.signature, p.from, p.matchKind)),
      givens = r.givens.map(g => HoleGiven(g.name, g.tpe, g.from)),
      types = r.types.map { t =>
        val ex = excerptOf(t.symbol)
        HoleType(t.symbol, if ex.isDefined then "project" else "classpath", t.signature, ex)
      },
      omitted =
        HoleOmitted(r.omitted.bindings, r.omitted.producers, r.omitted.givens, r.omitted.types)
    )
