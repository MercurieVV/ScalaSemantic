package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.meta.internal.semanticdb as s

/** Property-based complement to [[AnalyzerCoreSuite]] for the two orchestration branches that are
  * crisp boolean invariants rather than scenario-shaped output. Each is checked in
  * [[AnalyzerCoreSuite]] against a couple of hand-built cases; here they are generalised over
  * generated inputs so the invariant — not just the sampled points — is asserted:
  *
  *   - **methodSignature implicit-list flag**: a parameter list is flagged implicit iff it is
  *     non-empty AND every parameter is implicit (`params.nonEmpty && params.forall(isImplicit)`).
  *   - **resolveImplicits uniqueness**: `chosen` is defined iff exactly one candidate produces the
  *     queried type, and the candidate count equals the number of givens in the index.
  *
  * Both build small in-memory indexes (filter-safe, no dogfooding), matching the fixtures in
  * [[AnalyzerCoreSuite]].
  */
class AnalyzerCorePropertySuite extends munit.ScalaCheckSuite:

  private val P = "com/x/"

  private def tref(sym: String): s.Type = s.TypeRef(s.Type.Empty, sym, Nil)
  private val ObjectT = tref("java/lang/Object#")
  private val IntT = tref("scala/Int#")

  private def info(
      symbol: String,
      kind: s.SymbolInformation.Kind,
      displayName: String,
      signature: s.Signature,
      properties: Int = 0
  ): s.SymbolInformation =
    s.SymbolInformation(
      symbol = symbol,
      kind = kind,
      displayName = displayName,
      signature = signature,
      properties = properties
    )

  private def analyzer(symbols: s.SymbolInformation*): Analyzer =
    Analyzer(
      SemanticIndex(Vector(s.TextDocument(uri = "x.scala", symbols = symbols.toVector)))
    )

  private def tpe(v: String) = TypeSymbol.from(v).fold(fail(_), identity)
  private def method(v: String) = MethodSymbol.from(v).fold(fail(_), identity)

  // ---------------------------------------------------------------------------
  // methodSignature: implicit-list flag
  // ---------------------------------------------------------------------------

  private def param(symbol: String, display: String, isImplicit: Boolean) =
    info(
      symbol,
      s.SymbolInformation.Kind.PARAMETER,
      display,
      s.ValueSignature(IntT),
      if isImplicit then s.SymbolInformation.Property.IMPLICIT.value else 0
    )

  property(
    "methodSignature flags a parameter list implicit iff it is non-empty and all params are implicit"
  ):
    forAll(Gen.listOf(Gen.oneOf(true, false))) { flags =>
      val params = flags.zipWithIndex.map { (imp, i) =>
        param(s"${P}C#f().(p$i)", s"p$i", isImplicit = imp)
      }
      val m =
        info(
          s"${P}C#f().",
          s.SymbolInformation.Kind.METHOD,
          "f",
          s.MethodSignature(None, Seq(s.Scope(hardlinks = params.toVector)), IntT)
        )
      val lists = analyzer(m).methodSignature(method(s"${P}C#f().")).get.parameterLists
      // A method with one parameter list always yields exactly one ParameterList result.
      lists.map(_.isImplicit) == List(flags.nonEmpty && flags.forall(identity))
    }

  // ---------------------------------------------------------------------------
  // resolveImplicits: chosen iff unique
  // ---------------------------------------------------------------------------

  private val showCls =
    info(
      s"${P}Show#",
      s.SymbolInformation.Kind.CLASS,
      "Show",
      s.ClassSignature(None, List(ObjectT), s.Type.Empty, None)
    )

  private def givenObj(symbol: String, display: String) =
    info(
      symbol,
      s.SymbolInformation.Kind.OBJECT,
      display,
      s.ClassSignature(None, List(tref(s"${P}Show#"), ObjectT), s.Type.Empty, None),
      s.SymbolInformation.Property.IMPLICIT.value
    )

  property(
    "resolveImplicits: candidate count is the number of givens; chosen is defined iff exactly one"
  ):
    forAll(Gen.chooseNum(0, 5)) { n =>
      val givens = (0 until n).map(i => givenObj(s"${P}show$i.", s"show$i"))
      val r = analyzer((showCls +: givens)*).resolveImplicits(tpe(s"${P}Show#"))
      r.candidates.size == n && (r.chosen.isDefined == (n == 1))
    }
