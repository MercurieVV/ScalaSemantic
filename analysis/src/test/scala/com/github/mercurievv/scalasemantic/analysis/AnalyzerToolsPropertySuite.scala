package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.PositiveInt
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
