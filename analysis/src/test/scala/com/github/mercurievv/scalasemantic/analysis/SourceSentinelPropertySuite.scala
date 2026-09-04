package com.github.mercurievv.scalasemantic.analysis

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Property-based complement to [[SourceSentinelGoldenSuite]]: the golden locks one concrete
  * example, this generalises the round-trip law (`strip(inject(lines, notes)) == lines`) over
  * arbitrary line counts, indentation, and note placements/payloads.
  */
class SourceSentinelPropertySuite extends munit.ScalaCheckSuite:

  private type Note0 = SourceSentinel.Note

  // Printable, non-empty, no-newline text — safe as both a source line and a sentinel payload.
  private val genLine: Gen[String] =
    Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.const(' '))).map(_.mkString)

  private val genLines: Gen[Vector[String]] = Gen.listOf(genLine).map(_.toVector)

  private def genNotes(maxLine: Int): Gen[List[Note0]] =
    if maxLine < 0 then Gen.const(Nil)
    else
      Gen.listOf(
        for
          line <- Gen.choose(0, maxLine)
          payload <- Gen.alphaNumStr
        yield SourceSentinel.Note(line, payload)
      )

  property("strip(inject(lines, notes)) == lines, for any lines and any valid notes"):
    forAll(genLines) { lines =>
      forAll(genNotes(lines.length - 1)) { notes =>
        SourceSentinel.strip(SourceSentinel.inject(lines, notes)) == lines
      }
    }

  property("inject never changes the line count — it appends to existing lines, never inserts"):
    forAll(genLines) { lines =>
      forAll(genNotes(lines.length - 1)) { notes =>
        SourceSentinel.inject(lines, notes).size == lines.size
      }
    }
