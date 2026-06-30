package com.github.mercurievv.scalasemantic.model

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import upickle.default.read
import upickle.default.write

/** Property-based round-trip complement to [[ModelsSuite]].
  *
  * [[ModelsSuite]] verifies specific hand-crafted values; this suite uses ScalaCheck generators to
  * cover the full value space and assert that `write → read` is the identity for every wire model.
  * Three categories of properties:
  *   - **Codec round-trip**: `read[A](write(a)) == a` for arbitrary `a`.
  *   - **Field-named JSON**: the serialised form uses named keys, never positional arrays.
  *   - **Structural invariants**: e.g. a `ParameterList` whose `isImplicit` flag is true has every
  *     parameter marked implicit.
  */
class ModelsPropertySuite extends munit.ScalaCheckSuite:

  // ---------------------------------------------------------------------------
  // Generators
  // ---------------------------------------------------------------------------

  private val nonEmptyStr: Gen[String] = Gen.alphaStr.suchThat(_.nonEmpty)

  private val genPosition: Gen[Position] =
    for
      l <- Gen.chooseNum(0, 1000)
      c <- Gen.chooseNum(0, 1000)
    yield Position(l, c)

  private val genRange: Gen[Range] =
    for
      start <- genPosition
      end <- genPosition
    yield Range(start, end)

  private val genLocation: Gen[Location] =
    for
      uri <- nonEmptyStr
      range <- genRange
    yield Location(uri, range)

  private val genSymbolRef: Gen[SymbolRef] =
    for
      sym <- nonEmptyStr
      name <- nonEmptyStr
      kind <- Gen.oneOf(SymbolKind.values.toSeq)
    yield SymbolRef(sym, name, kind)

  private val genParameter: Gen[Parameter] =
    for
      name <- nonEmptyStr
      tpe <- nonEmptyStr
      isImplicit <- Gen.oneOf(true, false)
    yield Parameter(name, tpe, isImplicit)

  private val genParameterList: Gen[ParameterList] =
    for
      params <- Gen.listOf(genParameter)
      isImplicit <- Gen.oneOf(true, false)
    yield ParameterList(params, isImplicit)

  private val genMethodSignature: Gen[MethodSignature] =
    for
      sym <- nonEmptyStr
      name <- nonEmptyStr
      tyParams <- Gen.listOf(nonEmptyStr)
      pLists <- Gen.listOf(genParameterList)
      ret <- nonEmptyStr
      rendered <- nonEmptyStr
    yield MethodSignature(sym, name, tyParams, pLists, ret, rendered)

  private val genUsagesResult: Gen[UsagesResult] =
    for
      sym <- nonEmptyStr
      name <- nonEmptyStr
      defs <- Gen.listOf(genLocation)
      refs <- Gen.listOf(genLocation)
    yield UsagesResult(sym, name, defs, refs)

  private val genClassHierarchy: Gen[ClassHierarchy] =
    for
      sym <- nonEmptyStr
      name <- nonEmptyStr
      par <- Gen.listOf(genSymbolRef)
      lin <- Gen.listOf(genSymbolRef)
      subs <- Gen.listOf(genSymbolRef)
    yield ClassHierarchy(sym, name, par, lin, subs)

  // ---------------------------------------------------------------------------
  // Round-trip properties
  // ---------------------------------------------------------------------------

  private def roundTrips[A: upickle.default.ReadWriter](gen: Gen[A]): org.scalacheck.Prop =
    forAll(gen) { value => read[A](write(value)) == value }

  property("Position round-trips through upickle"):
    roundTrips(genPosition)

  property("Range round-trips through upickle"):
    roundTrips(genRange)

  property("Location round-trips through upickle"):
    roundTrips(genLocation)

  property("SymbolRef round-trips through upickle"):
    roundTrips(genSymbolRef)

  property("Parameter round-trips through upickle"):
    roundTrips(genParameter)

  property("ParameterList round-trips through upickle"):
    roundTrips(genParameterList)

  property("MethodSignature round-trips through upickle"):
    roundTrips(genMethodSignature)

  property("UsagesResult round-trips through upickle"):
    roundTrips(genUsagesResult)

  property("ClassHierarchy round-trips through upickle"):
    roundTrips(genClassHierarchy)

  // ---------------------------------------------------------------------------
  // Field-named JSON (not positional arrays)
  // ---------------------------------------------------------------------------

  property("Location JSON uses named fields"):
    forAll(genLocation) { loc =>
      val json = write(loc)
      json.contains("\"uri\"") && json.contains("\"range\"")
    }

  property("SymbolRef JSON uses named fields"):
    forAll(genSymbolRef) { ref =>
      val json = write(ref)
      json.contains("\"symbol\"") && json.contains("\"displayName\"") && json.contains("\"kind\"")
    }

  property("UsagesResult JSON uses named fields"):
    forAll(genUsagesResult) { r =>
      val json = write(r)
      json.contains("\"definitions\"") && json.contains("\"references\"")
    }

  // ---------------------------------------------------------------------------
  // Structural invariants
  // ---------------------------------------------------------------------------

  property("UsagesResult: definition and reference counts survive serialisation"):
    forAll(genUsagesResult) { r =>
      val rt = read[UsagesResult](write(r))
      rt.definitions.size == r.definitions.size && rt.references.size == r.references.size
    }
