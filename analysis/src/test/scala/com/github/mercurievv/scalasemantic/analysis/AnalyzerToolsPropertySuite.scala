package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.DocumentUri
import com.github.mercurievv.scalasemantic.model.InputTypes.PositiveInt
import com.github.mercurievv.scalasemantic.model.InputTypes.ScalaIdentifier
import com.github.mercurievv.scalasemantic.model.InputTypes.SourceRange
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.meta.internal.semanticdb as s

class AnalyzerToolsPropertySuite extends munit.ScalaCheckSuite:

  private def si(sym: String, kind: s.SymbolInformation.Kind, display: String) =
    s.SymbolInformation(
      symbol = sym,
      kind = kind,
      displayName = display
    )

  private def doc(uri: String, symbols: Seq[s.SymbolInformation]) =
    s.TextDocument(uri = uri, symbols = symbols.toVector)

  private val genAlphaStr: Gen[String] = Gen.nonEmptyListOf(Gen.alphaChar).map(_.mkString)

  property(
    "findSymbol ranks matches by exact > prefix > substring, then by length, then by symbol"
  ):
    forAll(genAlphaStr.suchThat(_.length >= 3)) { query =>
      val q = query.toLowerCase
      val genExact = Gen.oneOf(query, query.toLowerCase, query.toUpperCase)
      val genPrefix = for {
        suffix <- genAlphaStr
      } yield query + suffix
      val genSubstring = for {
        prefix <- genAlphaStr
        suffix <- genAlphaStr
      } yield prefix + query + suffix
      val genNoMatch = genAlphaStr.suchThat(s => !s.toLowerCase.contains(q))

      val genDisplayNames = for {
        exacts <- Gen.listOf(genExact)
        prefixes <- Gen.listOf(genPrefix)
        substrings <- Gen.listOf(genSubstring)
        noMatches <- Gen.listOf(genNoMatch)
      } yield (exacts ++ prefixes ++ substrings ++ noMatches).distinct

      forAll(genDisplayNames) { displayNames =>
        val symbols = displayNames.zipWithIndex.map { case (name, idx) =>
          si(s"f/Sym$idx#", s.SymbolInformation.Kind.CLASS, name)
        }
        val idx = SemanticIndex(Vector(doc("f.scala", symbols)))
        val az = Analyzer(idx)
        val limit = PositiveInt.from(symbols.size + 1, "limit").getOrElse(PositiveInt.DefaultLimit)
        val results = az.findSymbol(query, limit = limit)

        results.foreach { ref =>
          assert(
            ref.displayName.toLowerCase.contains(q),
            s"Returned non-matching symbol: ${ref.displayName}"
          )
        }

        val ranks = results.map { ref =>
          val name = ref.displayName.toLowerCase
          val r = if name == q then 0 else if name.startsWith(q) then 1 else 2
          (r, ref.displayName.length, ref.symbol)
        }
        val sortedRanks = ranks.sorted
        assertEquals(
          ranks,
          sortedRanks,
          s"Results not ranked correctly for query '$query'. Got: $ranks"
        )
      }
    }

  // ======================= extractMethodPlan classification ===========================
  //
  // Generalises the fixed DEF/REF occurrence-shape examples in AnalyzerToolsSuite (one example per
  // boolean combination of before/inside/after, ranged/range-less) to an arbitrary number of locals,
  // each with an arbitrary combination of occurrence shapes, checked against the classification
  // rule stated independently here:
  //   - a local is a **parameter** iff it has a ranged REF inside the selection and no ranged DEF
  //     inside the selection (a free read, not something the selection itself defines).
  //   - a local is a **return** iff it has a ranged DEF inside the selection and a ranged REF after
  //     it (the selection defines it and the value escapes).
  //   - range-less occurrences (`SymbolOccurrence(None, ...)`) never count towards either.
  // The exact rendered signature/call-site formatting (arity separators, tuple destructuring, `Unit`
  // for no returns) is not a boolean property and stays as concrete examples in AnalyzerToolsSuite.

  private val IntT = s.TypeRef(s.Type.Empty, "scala/Int#", Nil)

  private def exLocal(sym: String, display: String) =
    s.SymbolInformation(
      symbol = sym,
      kind = s.SymbolInformation.Kind.LOCAL,
      displayName = display,
      signature = s.ValueSignature(IntT)
    )

  private val exMethodSym = "ex/M#run()."
  private val exMethod = s.SymbolInformation(
    symbol = exMethodSym,
    kind = s.SymbolInformation.Kind.METHOD,
    displayName = "run",
    signature = s.MethodSignature(None, Nil, IntT)
  )

  private def occWithRange(
      sym: String,
      role: s.SymbolOccurrence.Role,
      l1: Int,
      c1: Int,
      l2: Int,
      c2: Int
  ) = s.SymbolOccurrence(Some(s.Range(l1, c1, l2, c2)), sym, role)
  private val DEF = s.SymbolOccurrence.Role.DEFINITION
  private val REF = s.SymbolOccurrence.Role.REFERENCE

  private def docWithOccs(
      uri: String,
      symbols: Seq[s.SymbolInformation],
      occs: Seq[s.SymbolOccurrence]
  ) = s.TextDocument(uri = uri, symbols = symbols.toVector, occurrences = occs.toVector)

  /** The occurrence shapes one local can carry, each independently present or absent. */
  private final case class LocalSpec(
      defBefore: Boolean, // ranged DEF before the selection
      defInside: Boolean, // ranged DEF inside the selection
      defRangeless: Boolean, // range-less DEF (must never count)
      refInside: Boolean, // ranged REF inside the selection
      refAfter: Boolean, // ranged REF after the selection
      refRangeless: Boolean // range-less REF, inside or after (must never count)
  )

  private val genLocalSpec: Gen[LocalSpec] =
    for
      defBefore <- Gen.oneOf(true, false)
      defInside <- Gen.oneOf(true, false)
      defRangeless <- Gen.oneOf(true, false)
      refInside <- Gen.oneOf(true, false)
      refAfter <- Gen.oneOf(true, false)
      refRangeless <- Gen.oneOf(true, false)
    yield LocalSpec(defBefore, defInside, defRangeless, refInside, refAfter, refRangeless)

  private val genLocalSpecs: Gen[List[LocalSpec]] =
    Gen.choose(1, 3).flatMap(n => Gen.listOfN(n, genLocalSpec))

  property(
    "extractMethodPlan: parameter iff ranged-read-inside without ranged-def-inside; " +
      "return iff ranged-def-inside with a ranged-read-after; range-less occurrences never count"
  ):
    forAll(genLocalSpecs) { specs =>
      val locals = specs.zipWithIndex.map { case (spec, i) => (s"local$i", s"n$i", spec) }
      val symbols = exMethod +: locals.map { case (sym, disp, _) => exLocal(sym, disp) }
      val occs = occWithRange(exMethodSym, DEF, 0, 2, 0, 5) +: locals.flatMap {
        case (sym, _, spec) =>
          List(
            Option.when(spec.defBefore)(occWithRange(sym, DEF, 0, 1, 0, 2)),
            Option.when(spec.defInside)(occWithRange(sym, DEF, 3, 1, 3, 2)),
            Option.when(spec.defRangeless)(s.SymbolOccurrence(None, sym, DEF)),
            Option.when(spec.refInside)(occWithRange(sym, REF, 4, 1, 4, 2)),
            Option.when(spec.refAfter)(occWithRange(sym, REF, 6, 1, 6, 2)),
            Option.when(spec.refRangeless)(s.SymbolOccurrence(None, sym, REF))
          ).flatten
      }
      val az = Analyzer(SemanticIndex(Vector(docWithOccs("ex.scala", symbols, occs))))
      val range = SourceRange.from(2, 0, 5, 0).fold(err => fail(err), identity)
      val name = ScalaIdentifier.from("gen0").fold(err => fail(err), identity)
      val docUri = DocumentUri.from("ex.scala").fold(err => fail(err), identity)
      val plan = az.extractMethodPlan(docUri, range, name).getOrElse(fail("not indexed"))

      val expectedParams = locals.collect {
        case (_, disp, spec) if spec.refInside && !spec.defInside => disp
      }.toSet
      val expectedReturns = locals.collect {
        case (_, disp, spec) if spec.defInside && spec.refAfter => disp
      }.toSet

      plan.parameters.map(_.name).toSet == expectedParams &&
      plan.returns.map(_.name).toSet == expectedReturns
    }
