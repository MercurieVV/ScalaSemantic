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
  *   - **Structural invariants**: e.g. counts and flags survive the codec round-trip exactly.
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

  private val genOverloadsResult: Gen[OverloadsResult] =
    for
      name <- nonEmptyStr
      overloads <- Gen.listOf(genMethodSignature)
    yield OverloadsResult(name, overloads)

  private val genMemberInfo: Gen[MemberInfo] =
    for
      sym <- nonEmptyStr
      name <- nonEmptyStr
      kind <- Gen.oneOf(SymbolKind.values.toSeq)
      decl <- genSymbolRef
    yield MemberInfo(sym, name, kind, decl)

  private val genMembersResult: Gen[MembersResult] =
    for
      sym <- nonEmptyStr
      name <- nonEmptyStr
      declared <- Gen.listOf(genMemberInfo)
      inherited <- Gen.listOf(genMemberInfo)
    yield MembersResult(sym, name, declared, inherited)

  private val genImplicitCandidate: Gen[ImplicitCandidate] =
    for
      target <- genSymbolRef
      tpe <- nonEmptyStr
      fromExplicitImport <- Gen.oneOf(true, false)
    yield ImplicitCandidate(target, tpe, fromExplicitImport)

  private val genImplicitResolution: Gen[ImplicitResolution] =
    for
      queryType <- nonEmptyStr
      chosen <- Gen.option(genSymbolRef)
      candidates <- Gen.listOf(genImplicitCandidate)
    yield ImplicitResolution(queryType, chosen, candidates)

  private val genImplicitChainStep: Gen[ImplicitChainStep] =
    for
      target <- genSymbolRef
      tpe <- nonEmptyStr
      dependsOn <- Gen.listOf(nonEmptyStr)
    yield ImplicitChainStep(target, tpe, dependsOn)

  private val genImplicitChain: Gen[ImplicitChain] =
    for
      queryType <- nonEmptyStr
      steps <- Gen.listOf(genImplicitChainStep)
    yield ImplicitChain(queryType, steps)

  private val genTypeAtPosition: Gen[TypeAtPosition] =
    for
      location <- genLocation
      symbol <- nonEmptyStr
      displayName <- nonEmptyStr
      tpe <- nonEmptyStr
    yield TypeAtPosition(location, symbol, displayName, tpe)

  private val genCallEdge: Gen[CallEdge] =
    for
      from <- genSymbolRef
      to <- genSymbolRef
      at <- genLocation
    yield CallEdge(from, to, at)

  private val genCallGraphPath: Gen[CallGraphPath] =
    for
      from <- genSymbolRef
      to <- genSymbolRef
      path <- Gen.listOf(genSymbolRef)
      edges <- Gen.listOf(genCallEdge)
    yield CallGraphPath(from, to, path, edges)

  private val genRenameEdit: Gen[RenameEdit] =
    for
      uri <- nonEmptyStr
      range <- genRange
      oldText <- nonEmptyStr
      newText <- nonEmptyStr
    yield RenameEdit(uri, range, oldText, newText)

  private val genRenamePlan: Gen[RenamePlan] =
    for
      symbol <- nonEmptyStr
      fromName <- nonEmptyStr
      toName <- nonEmptyStr
      edits <- Gen.listOf(genRenameEdit)
    yield RenamePlan(symbol, fromName, toName, edits.size, edits)

  private val genMoveImport: Gen[MoveImport] =
    for
      uri <- nonEmptyStr
      removeImport <- Gen.alphaStr
      addImport <- Gen.alphaStr
    yield MoveImport(uri, removeImport, addImport)

  private val genExtractBinding: Gen[ExtractBinding] =
    for
      name <- nonEmptyStr
      tpe <- nonEmptyStr
    yield ExtractBinding(name, tpe)

  private val genDependencyCycle: Gen[DependencyCycle] =
    for
      dimension <- nonEmptyStr
      members <- Gen.listOf(nonEmptyStr)
    yield DependencyCycle(dimension, members)

  private val genDimensionMetrics: Gen[DimensionMetrics] =
    for
      afferent <- Gen.chooseNum(0, 100)
      efferent <- Gen.chooseNum(0, 100)
      layer <- Gen.chooseNum(0, 10)
      centrality <- Gen.chooseNum(0.0, 1.0)
      sccSize <- Gen.chooseNum(1, 5)
      inCycle <- Gen.oneOf(true, false)
    yield
      val instability =
        if afferent + efferent == 0 then 0.0
        else efferent.toDouble / (afferent + efferent)
      DimensionMetrics(afferent, efferent, instability, layer, centrality, sccSize, inCycle)

  private val genSourceAnnotation: Gen[SourceAnnotation] =
    for
      line <- Gen.chooseNum(0, 1000)
      char <- Gen.chooseNum(0, 1000)
      kind <- Gen.oneOf(
        "implicit",
        "inferred-type",
        "implicit-conversion",
        "inferred-type-args"
      )
      text <- nonEmptyStr
    yield SourceAnnotation(line, char, kind, text)

  private val genDuplicateOccurrence: Gen[DuplicateOccurrence] =
    for
      location <- genLocation
      enclosingMethod <- Gen.option(nonEmptyStr)
    yield DuplicateOccurrence(location, enclosingMethod)

  private val genDuplicationGroup: Gen[DuplicationGroup] =
    for
      size <- Gen.chooseNum(2, 5)
      astNodeCount <- Gen.chooseNum(1, 100)
      occurrences <- Gen.listOfN(size, genDuplicateOccurrence)
    yield DuplicationGroup(size, astNodeCount, occurrences)

  private val genDuplicationsResult: Gen[DuplicationsResult] =
    Gen.listOf(genDuplicationGroup).map(DuplicationsResult.apply)

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

  property("OverloadsResult round-trips through upickle"):
    roundTrips(genOverloadsResult)

  property("MemberInfo round-trips through upickle"):
    roundTrips(genMemberInfo)

  property("MembersResult round-trips through upickle"):
    roundTrips(genMembersResult)

  property("ImplicitCandidate round-trips through upickle"):
    roundTrips(genImplicitCandidate)

  property("ImplicitResolution round-trips through upickle"):
    roundTrips(genImplicitResolution)

  property("ImplicitChainStep round-trips through upickle"):
    roundTrips(genImplicitChainStep)

  property("ImplicitChain round-trips through upickle"):
    roundTrips(genImplicitChain)

  property("TypeAtPosition round-trips through upickle"):
    roundTrips(genTypeAtPosition)

  property("CallEdge round-trips through upickle"):
    roundTrips(genCallEdge)

  property("CallGraphPath round-trips through upickle"):
    roundTrips(genCallGraphPath)

  property("RenameEdit round-trips through upickle"):
    roundTrips(genRenameEdit)

  property("RenamePlan round-trips through upickle"):
    roundTrips(genRenamePlan)

  property("MoveImport round-trips through upickle"):
    roundTrips(genMoveImport)

  property("ExtractBinding round-trips through upickle"):
    roundTrips(genExtractBinding)

  property("DependencyCycle round-trips through upickle"):
    roundTrips(genDependencyCycle)

  property("DimensionMetrics round-trips through upickle"):
    roundTrips(genDimensionMetrics)

  property("SourceAnnotation round-trips through upickle"):
    roundTrips(genSourceAnnotation)

  property("DuplicateOccurrence round-trips through upickle"):
    roundTrips(genDuplicateOccurrence)

  property("DuplicationGroup round-trips through upickle"):
    roundTrips(genDuplicationGroup)

  property("DuplicationsResult round-trips through upickle"):
    roundTrips(genDuplicationsResult)

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

  property("MethodSignature JSON uses named fields"):
    forAll(genMethodSignature) { sig =>
      val json = write(sig)
      json.contains("\"symbol\"") && json.contains("\"returnType\"") && json.contains(
        "\"rendered\""
      )
    }

  property("ClassHierarchy JSON uses named fields"):
    forAll(genClassHierarchy) { h =>
      val json = write(h)
      json.contains("\"parents\"") && json.contains("\"linearization\"") && json
        .contains("\"knownSubtypes\"")
    }

  property("ImplicitResolution JSON uses named fields"):
    forAll(genImplicitResolution) { r =>
      val json = write(r)
      json.contains("\"queryType\"") && json.contains("\"candidates\"")
    }

  property("CallGraphPath JSON uses named fields"):
    forAll(genCallGraphPath) { p =>
      val json = write(p)
      json.contains("\"path\"") && json.contains("\"edges\"")
    }

  property("RenamePlan JSON uses named fields"):
    forAll(genRenamePlan) { p =>
      val json = write(p)
      json.contains("\"fromName\"") && json.contains("\"toName\"") && json.contains("\"editCount\"")
    }

  // ---------------------------------------------------------------------------
  // Structural invariants
  // ---------------------------------------------------------------------------

  property("UsagesResult: definition and reference counts survive serialisation"):
    forAll(genUsagesResult) { r =>
      val rt = read[UsagesResult](write(r))
      rt.definitions.size == r.definitions.size && rt.references.size == r.references.size
    }

  property("MembersResult: declared and inherited counts survive serialisation"):
    forAll(genMembersResult) { r =>
      val rt = read[MembersResult](write(r))
      rt.declared.size == r.declared.size && rt.inherited.size == r.inherited.size
    }

  property("ClassHierarchy: list sizes survive serialisation"):
    forAll(genClassHierarchy) { h =>
      val rt = read[ClassHierarchy](write(h))
      rt.linearization.size == h.linearization.size && rt.knownSubtypes.size == h.knownSubtypes.size
    }

  property("MethodSignature: parameterLists and typeParameters counts survive serialisation"):
    forAll(genMethodSignature) { sig =>
      val rt = read[MethodSignature](write(sig))
      rt.parameterLists.size == sig.parameterLists.size &&
      rt.typeParameters.size == sig.typeParameters.size
    }

  property("OverloadsResult: overload count survives serialisation"):
    forAll(genOverloadsResult) { r =>
      val rt = read[OverloadsResult](write(r))
      rt.overloads.size == r.overloads.size
    }

  property("ImplicitChain: step count survives serialisation"):
    forAll(genImplicitChain) { c =>
      val rt = read[ImplicitChain](write(c))
      rt.steps.size == c.steps.size
    }

  property("ImplicitResolution: candidate count survives serialisation"):
    forAll(genImplicitResolution) { r =>
      val rt = read[ImplicitResolution](write(r))
      rt.candidates.size == r.candidates.size
    }

  property("CallGraphPath: path and edge counts survive serialisation"):
    forAll(genCallGraphPath) { p =>
      val rt = read[CallGraphPath](write(p))
      rt.path.size == p.path.size && rt.edges.size == p.edges.size
    }

  property("RenamePlan: editCount always equals edits.size after round-trip"):
    forAll(genRenamePlan) { plan =>
      val rt = read[RenamePlan](write(plan))
      rt.editCount == rt.edits.size
    }

  property("DuplicationsResult: group count survives serialisation"):
    forAll(genDuplicationsResult) { r =>
      val rt = read[DuplicationsResult](write(r))
      rt.groups.size == r.groups.size
    }

  property("SymbolKind values all round-trip through the ReadWriter"):
    forAll(Gen.oneOf(SymbolKind.values.toSeq)) { kind =>
      val ref = SymbolRef("s#", "S", kind)
      read[SymbolRef](write(ref)).kind == kind
    }

  property("DimensionMetrics: instability is in [0.0, 1.0]"):
    forAll(genDimensionMetrics) { m =>
      m.instability >= 0.0 && m.instability <= 1.0
    }

  property("DimensionMetrics: instability formula matches afferent/efferent"):
    forAll(genDimensionMetrics) { m =>
      val expected =
        if m.afferent + m.efferent == 0 then 0.0
        else m.efferent.toDouble / (m.afferent + m.efferent)
      math.abs(m.instability - expected) < 1e-9
    }
