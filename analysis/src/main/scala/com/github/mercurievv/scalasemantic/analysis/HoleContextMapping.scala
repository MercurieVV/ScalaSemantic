package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.HoleBinding
import com.github.mercurievv.scalasemantic.model.HoleContextResult
import com.github.mercurievv.scalasemantic.model.HoleExpected
import com.github.mercurievv.scalasemantic.model.HoleGiven
import com.github.mercurievv.scalasemantic.model.HoleMarker
import com.github.mercurievv.scalasemantic.model.HoleOmitted
import com.github.mercurievv.scalasemantic.model.HoleProducer
import com.github.mercurievv.scalasemantic.model.HoleType
import com.github.mercurievv.scalasemantic.pc.HoleContext

/** The presentation compiler's hole answer as the domain model. It lives here, not beside the
  * model: the domain module knows nothing of the presentation compiler.
  */
object HoleContextMapping:
  /** Pure conversion from the pc result. `isProject` decides `source` (the index knows the symbol's
    * definition) and `excerptOf` supplies the excerpt — independently: a project type whose file is
    * unreadable stays `source: "project"` with `excerpt: null`, rather than claiming to be a
    * classpath type.
    */
  def from(
      r: HoleContext.Result,
      isProject: String => Boolean,
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
      types = r.types.map(t =>
        HoleType(
          t.symbol,
          if isProject(t.symbol) then "project" else "classpath",
          t.signature,
          excerptOf(t.symbol)
        )
      ),
      omitted =
        HoleOmitted(r.omitted.bindings, r.omitted.producers, r.omitted.givens, r.omitted.types)
    )
