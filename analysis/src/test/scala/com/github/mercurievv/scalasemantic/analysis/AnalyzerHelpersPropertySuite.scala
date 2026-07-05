package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import com.github.mercurievv.scalasemantic.testing.PropertyGens
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.meta.internal.semanticdb as s
import scala.util.Try

/** Property-based tests for the pure helper methods in [[AnalyzerHelpers]] that are stateless or
  * only depend on the empty index. These are fast, filter-safe, and cover behaviours that the
  * example-based [[AnalyzerHelpersSuite]] expresses as a fixed list of concrete inputs.
  *
  * Properties covered:
  *   - **rangeContains**: the inclusive-start/exclusive-end containment contract expressed as
  *     forAll (replaces four concrete boundary examples with a single predicate).
  *   - **isImplicit**: the IMPLICIT-bit test is true iff the bit is set, for all bitmask values.
  *   - **packageDotted**: output never contains slashes; empty input yields empty output.
  *   - **joinFqn**: identity laws and separator placement.
  *   - **globMatcher with None**: always returns true (replaces a single concrete example).
  *   - **rangeSpan**: the exact line-dominant formula, and non-negativity, over any well-formed
  *     range (replaces two fixed-number examples).
  *   - **alreadyAscribed**: agreement with an independently-written scan over arbitrary
  *     bracket/colon/equals strings of any nesting depth, plus the out-of-range boundary.
  *   - **isGivenDefinition**: the implicit-bit-AND-kind boolean law, for every `Kind` (replaces
  *     four fixed kind/bit combinations).
  *   - **renderType**: per-constructor rendering laws (recursive prefix/suffix/join/delegate rules)
  *     over arbitrarily deep generated type trees, plus a never-throws smoke property.
  *   - **renderConstant**: the exact literal-formatting rule for every constant kind, over
  *     arbitrary numeric/string/char values (not just one hardcoded example per kind).
  *   - **renderMethod**: the exact `def name[tparams](params): ret` assembly rule over arbitrary
  *     names, type-parameter counts, and parameter lists (reusing [[PropertyGens.parameterList]]).
  *
  * Note: `rangeContains` has a Stainless-verified `require` that the s.Range must be well-formed
  * (`endLine >= startLine`; if same line `endChar >= startChar`; all values non-negative) and the
  * query position must also be non-negative. Generators therefore always produce valid inputs.
  */
class AnalyzerHelpersPropertySuite extends munit.ScalaCheckSuite:

  private val h = AnalyzerHelpers(SemanticIndex.fromRoots(Nil))
  private val IMPLICIT_BIT = s.SymbolInformation.Property.IMPLICIT.value

  // ---------------------------------------------------------------------------
  // Generator helpers that satisfy rangeContains preconditions
  // ---------------------------------------------------------------------------

  /** A well-formed s.Range: all fields non-negative, endLine >= startLine, if single line then
    * endChar >= startChar. The `require` in PureKernels.rangeContains enforces exactly this.
    */
  private val genValidRange: Gen[s.Range] =
    for
      sl <- Gen.chooseNum(0, 8)
      sc <- Gen.chooseNum(0, 8)
      el <- Gen.chooseNum(0, 8).map(d => sl + d) // endLine >= startLine
      ec <- Gen.chooseNum(0, 8).flatMap { d =>
        if el == sl then Gen.const(sc + d) // endChar >= startChar on same line
        else Gen.chooseNum(0, 8)
      }
    yield s.Range(sl, sc, el, ec)

  /** A query position: both fields non-negative. */
  private val genQueryPos: Gen[(Int, Int)] =
    for
      ql <- Gen.chooseNum(0, 12)
      qc <- Gen.chooseNum(0, 12)
    yield (ql, qc)

  // ---------------------------------------------------------------------------
  // rangeContains
  // ---------------------------------------------------------------------------

  property("rangeContains satisfies the full inclusive-start / exclusive-end contract"):
    forAll(genValidRange, genQueryPos) { (r, pos) =>
      val ql = pos._1
      val qc = pos._2
      val expected =
        if ql < r.startLine then false
        else if ql > r.endLine then false
        else if ql == r.startLine && ql == r.endLine then
          qc >= r.startCharacter && qc < r.endCharacter
        else if ql == r.startLine then qc >= r.startCharacter
        else if ql == r.endLine then qc < r.endCharacter
        else true
      h.rangeContains(r, ql, qc) == expected
    }

  property(
    "rangeContains: a point at exactly the start position is always inside (unless empty range)"
  ):
    forAll(genValidRange) { r =>
      val startIsInside = h.rangeContains(r, r.startLine, r.startCharacter)
      // An empty (degenerate) range where start == end contains nothing
      val isEmpty = r.startLine == r.endLine && r.startCharacter == r.endCharacter
      if isEmpty then !startIsInside
      else startIsInside
    }

  property("rangeContains: a point strictly before the start line is never inside"):
    forAll(genValidRange, Gen.chooseNum(0, 8)) { (r, qc) =>
      if r.startLine == 0 then true // can't go before line 0
      else !h.rangeContains(r, r.startLine - 1, qc)
    }

  property("rangeContains: the end position (endLine, endChar) is never inside"):
    forAll(genValidRange) { r =>
      !h.rangeContains(r, r.endLine, r.endCharacter)
    }

  property("rangeContains: a point strictly after the end line is never inside"):
    forAll(genValidRange, Gen.chooseNum(0, 8)) { (r, qc) =>
      !h.rangeContains(r, r.endLine + 1, qc)
    }

  // ---------------------------------------------------------------------------
  // isImplicit
  // ---------------------------------------------------------------------------

  property("isImplicit: true iff the IMPLICIT bit is set in properties"):
    forAll(Gen.chooseNum(0, 255)) { bits =>
      val info = s.SymbolInformation(symbol = "a/x.", properties = bits)
      h.isImplicit(info) == ((bits & IMPLICIT_BIT) != 0)
    }

  property("isImplicit: setting only the IMPLICIT bit makes it true"):
    val info = s.SymbolInformation(symbol = "a/x.", properties = IMPLICIT_BIT)
    h.isImplicit(info)

  property("isImplicit: zero properties means not implicit"):
    val info = s.SymbolInformation(symbol = "a/x.", properties = 0)
    !h.isImplicit(info)

  // ---------------------------------------------------------------------------
  // packageDotted
  // ---------------------------------------------------------------------------

  property("packageDotted: output contains no slashes"):
    forAll(Gen.listOf(Gen.alphaLowerChar).map(_.mkString)) { seg =>
      val pkg = if seg.isEmpty then "" else s"com/$seg/"
      !h.packageDotted(pkg).contains("/")
    }

  property("packageDotted: empty string yields empty string"):
    h.packageDotted("") == ""

  property("packageDotted: a two-segment package produces dot-separated output"):
    h.packageDotted("com/example/") == "com.example"

  // ---------------------------------------------------------------------------
  // joinFqn
  // ---------------------------------------------------------------------------

  property("joinFqn: empty pkg yields just the name"):
    forAll(Gen.alphaStr.suchThat(_.nonEmpty)) { name =>
      h.joinFqn("", name) == name
    }

  property("joinFqn: non-empty pkg is dot-separated from name"):
    forAll(
      Gen.alphaStr.suchThat(_.nonEmpty),
      Gen.alphaStr.suchThat(_.nonEmpty)
    ) { (pkg, name) =>
      val joined = h.joinFqn(pkg, name)
      joined == s"$pkg.$name"
    }

  property("joinFqn: result contains both pkg and name"):
    forAll(
      Gen.alphaStr.suchThat(_.nonEmpty),
      Gen.alphaStr.suchThat(_.nonEmpty)
    ) { (pkg, name) =>
      val joined = h.joinFqn(pkg, name)
      joined.contains(pkg) && joined.contains(name)
    }

  // ---------------------------------------------------------------------------
  // globMatcher
  // ---------------------------------------------------------------------------

  property("globMatcher(None) always returns true"):
    forAll(Gen.alphaNumStr) { s =>
      h.globMatcher(None)(s)
    }

  property("globMatcher(Some(literal)) returns true iff the string contains the literal"):
    forAll(Gen.alphaStr.suchThat(_.nonEmpty), Gen.alphaStr.suchThat(_.nonEmpty)) {
      (lit, haystack) =>
        // Only test the non-wildcard case (no '*' in pattern)
        if !lit.contains("*") then
          val matcher = h.globMatcher(Some(lit))
          matcher(haystack) == haystack.contains(lit)
        else true
    }

  // ---------------------------------------------------------------------------
  // linearize (transitive parents over an arbitrary class DAG)
  // ---------------------------------------------------------------------------
  //
  // Generalises the fixed diamond in AnalyzerHelpersSuite ("linearize de-duplicates a diamond"):
  // over any acyclic class hierarchy, linearize must (a) return each ancestor exactly once, and
  // (b) return exactly the transitive parent set of the root. Acyclicity is guaranteed by only
  // letting a class `c$i` extend classes `c$j` with `j > i`, so parent edges always point to a
  // strictly higher index and no cycle can form.

  /** A class hierarchy over `c0 .. c{n-1}`: the built index plus the root symbol and the raw parent
    * map used to compute the expected transitive-ancestor set independently.
    */
  private val genClassDag: Gen[(SemanticIndex, String, Map[String, Set[String]])] =
    def sym(i: Int): String = s"d/c$i#"
    for
      n <- Gen.chooseNum(1, 8)
      parentLists <- Gen.sequence[List[List[Int]], List[Int]](
        (0 until n).toList.map { i =>
          Gen.someOf((i + 1) until n).map(_.toList)
        }
      )
    yield
      val parentMap = (0 until n).map(i => sym(i) -> parentLists(i).map(sym).toSet).toMap
      val symbols = (0 until n).toVector.map { i =>
        s.SymbolInformation(
          symbol = sym(i),
          kind = s.SymbolInformation.Kind.CLASS,
          displayName = s"c$i",
          signature = s.ClassSignature(
            None,
            parentLists(i).map(p => s.TypeRef(s.Type.Empty, sym(p), Nil)),
            s.Type.Empty,
            None
          )
        )
      }
      val index = new SemanticIndex(Vector(s.TextDocument(uri = "d.scala", symbols = symbols)))
      (index, sym(0), parentMap)

  /** The transitive parents of `root` in `parents` (root excluded), computed by a plain
    * reachability walk that is independent of the linearize implementation under test.
    */
  private def transitiveAncestors(root: String, parents: Map[String, Set[String]]): Set[String] =
    @annotation.tailrec
    def loop(frontier: List[String], seen: Set[String]): Set[String] =
      frontier match
        case Nil          => seen
        case head :: tail =>
          val next = parents.getOrElse(head, Set.empty).filterNot(seen.contains)
          loop(next.toList ::: tail, seen ++ next)
    loop(List(root), Set.empty)

  property("linearize returns each ancestor exactly once (no duplicates) over any class DAG"):
    forAll(genClassDag) { (index, root, _) =>
      val lin = AnalyzerHelpers(index).linearize(root)
      lin.distinct == lin
    }

  property("linearize yields exactly the transitive parent set of the root"):
    forAll(genClassDag) { (index, root, parents) =>
      val lin = AnalyzerHelpers(index).linearize(root)
      lin.toSet == transitiveAncestors(root, parents)
    }

  // ---------------------------------------------------------------------------
  // rangeSpan
  // ---------------------------------------------------------------------------

  property("rangeSpan matches the line-dominant formula (lines x10000 + character delta)"):
    forAll(genValidRange) { r =>
      h.rangeSpan(r) == (r.endLine.toLong - r.startLine) * 10000L +
        (r.endCharacter.toLong - r.startCharacter)
    }

  property("rangeSpan is always non-negative for a well-formed range"):
    forAll(genValidRange) { r =>
      h.rangeSpan(r) >= 0L
    }

  // ---------------------------------------------------------------------------
  // alreadyAscribed
  // ---------------------------------------------------------------------------
  //
  // Generalises the fixed line/nameStart examples in AnalyzerHelpersSuite over synthetic
  // bracket/colon/equals strings: an independently-written (imperative, not tail-recursive)
  // scan must agree with the production implementation for every nesting depth and start index.

  private val genAscribedChar: Gen[Char] = Gen.oneOf(':', '=', '(', ')', '[', ']', 'x')
  private val genAscribedLine: Gen[String] =
    Gen.choose(0, 12).flatMap(n => Gen.listOfN(n, genAscribedChar).map(_.mkString))
  private val genAscribedStart: Gen[Int] = Gen.chooseNum(-3, 15)

  /** An alternative (imperative, index-mutating) implementation of the same "top-level colon before
    * top-level equals" scan that [[AnalyzerHelpers.alreadyAscribed]] performs, used as an
    * independent oracle rather than a copy of the production algorithm.
    */
  private def oracleAlreadyAscribed(line: String, nameStart: Int): Boolean =
    if nameStart < 0 || nameStart >= line.length then false
    else
      val (_, found) =
        line.substring(nameStart).foldLeft((0, Option.empty[Boolean])) { case ((depth, acc), c) =>
          acc match
            case some @ Some(_) => (depth, some) // already decided; stop reacting to further chars
            case None           =>
              c match
                case '=' if depth == 0 => (depth, Some(false))
                case ':' if depth == 0 => (depth, Some(true))
                case '(' | '['         => (depth + 1, None)
                case ')' | ']'         => (depth - 1, None)
                case _                 => (depth, None)
        }
      found.getOrElse(false)

  property(
    "alreadyAscribed agrees with an independent depth-tracking scan for any bracket/colon/equals string"
  ):
    forAll(genAscribedLine, genAscribedStart) { (line, start) =>
      h.alreadyAscribed(line, start) == oracleAlreadyAscribed(line, start)
    }

  property("alreadyAscribed is always false when nameStart is out of the line's bounds"):
    forAll(genAscribedLine, Gen.chooseNum(-5, -1)) { (line, start) =>
      !h.alreadyAscribed(line, start)
    } && forAll(genAscribedLine) { line =>
      !h.alreadyAscribed(line, line.length) && !h.alreadyAscribed(line, line.length + 5)
    }

  // ---------------------------------------------------------------------------
  // isGivenDefinition
  // ---------------------------------------------------------------------------

  private val genKind: Gen[s.SymbolInformation.Kind] = Gen.oneOf(
    s.SymbolInformation.Kind.OBJECT,
    s.SymbolInformation.Kind.METHOD,
    s.SymbolInformation.Kind.FIELD,
    s.SymbolInformation.Kind.CLASS,
    s.SymbolInformation.Kind.TRAIT,
    s.SymbolInformation.Kind.PARAMETER,
    s.SymbolInformation.Kind.UNKNOWN_KIND
  )

  property(
    "isGivenDefinition (empty index, top-level symbol) is true iff implicit AND kind is OBJECT or METHOD"
  ):
    forAll(genKind, Gen.oneOf(true, false)) { (kind, implicit_) =>
      val info = s.SymbolInformation(
        symbol = "g/x.",
        kind = kind,
        properties = if implicit_ then IMPLICIT_BIT else 0
      )
      val expected = implicit_ &&
        (kind == s.SymbolInformation.Kind.OBJECT || kind == s.SymbolInformation.Kind.METHOD)
      h.isGivenDefinition(info) == expected
    }

  // ---------------------------------------------------------------------------
  // renderType / renderConstant / renderMethod
  // ---------------------------------------------------------------------------
  //
  // genType builds arbitrarily deep synthetic type trees over a small alphabet of fabricated
  // global symbols ("a/Name#"); on an empty index, SemanticIndex.displayName falls back to a
  // global symbol's last descriptor name, so `refOf("Foo")` always renders its base as "Foo".

  private val leafNames = List("Foo", "Bar", "Baz", "Int", "String")

  private def refOf(name: String, args: List[s.Type] = Nil): s.Type =
    s.TypeRef(s.Type.Empty, s"a/$name#", args)

  private def genLeaf: Gen[s.Type] = Gen.oneOf(leafNames).map(n => refOf(n))

  private def genType(depth: Int): Gen[s.Type] =
    if depth <= 0 then genLeaf
    else
      Gen.oneOf(
        genLeaf,
        for
          n <- Gen.oneOf(leafNames)
          argc <- Gen.chooseNum(0, 2)
          args <- Gen.listOfN(argc, genType(depth - 1))
        yield refOf(n, args),
        genType(depth - 1).map(s.ByNameType(_)),
        genType(depth - 1).map(s.RepeatedType(_)),
        Gen.listOfN(2, genType(depth - 1)).map(s.WithType(_)),
        Gen.listOfN(2, genType(depth - 1)).map(s.IntersectionType(_)),
        Gen.listOfN(2, genType(depth - 1)).map(s.UnionType(_)),
        genType(depth - 1).map(t => s.AnnotatedType(Nil, t)),
        genType(depth - 1).map(t => s.ExistentialType(t, None)),
        genType(depth - 1).map(t => s.UniversalType(None, t)),
        genType(depth - 1).map(t => s.StructuralType(t, None))
      )

  private val genConstant: Gen[s.Constant] = Gen.oneOf(
    Gen.chooseNum(Int.MinValue, Int.MaxValue).map(s.IntConstant(_)),
    Gen.chooseNum(Long.MinValue, Long.MaxValue).map(s.LongConstant(_)),
    Gen.chooseNum(Float.MinValue, Float.MaxValue).map(s.FloatConstant(_)),
    Gen.chooseNum(Double.MinValue, Double.MaxValue).map(s.DoubleConstant(_)),
    Gen.oneOf(true, false).map(s.BooleanConstant(_)),
    Gen.chooseNum(0, 0xd7ff).map(s.CharConstant(_)),
    Gen.asciiPrintableStr.map(s.StringConstant(_)),
    Gen.chooseNum(Short.MinValue.toInt, Short.MaxValue.toInt).map(s.ShortConstant(_)),
    Gen.chooseNum(Byte.MinValue.toInt, Byte.MaxValue.toInt).map(s.ByteConstant(_))
  )

  property("renderType never throws for any generated type shape, however deeply nested"):
    forAll(genType(4)) { t =>
      Try(h.renderType(t)).isSuccess
    }

  property(
    "renderType(TypeRef) renders the symbol's simple name, bracketing recursively-rendered args iff non-empty"
  ):
    forAll(Gen.oneOf(leafNames), Gen.listOf(genType(1))) { (name, args) =>
      val rendered = h.renderType(refOf(name, args))
      if args.isEmpty then rendered == name
      else rendered == args.map(h.renderType).mkString(s"$name[", ", ", "]")
    }

  property("renderType(ByNameType(t)) always prefixes '=> ' onto the inner rendering"):
    forAll(genType(2)) { t =>
      h.renderType(s.ByNameType(t)) == s"=> ${h.renderType(t)}"
    }

  property("renderType(RepeatedType(t)) always suffixes '*' onto the inner rendering"):
    forAll(genType(2)) { t =>
      h.renderType(s.RepeatedType(t)) == s"${h.renderType(t)}*"
    }

  property("renderType(WithType) joins renders with ' with '"):
    forAll(Gen.listOfN(3, genType(1))) { ts =>
      h.renderType(s.WithType(ts)) == ts.map(h.renderType).mkString(" with ")
    }

  property("renderType(IntersectionType) joins renders with ' & '"):
    forAll(Gen.listOfN(3, genType(1))) { ts =>
      h.renderType(s.IntersectionType(ts)) == ts.map(h.renderType).mkString(" & ")
    }

  property("renderType(UnionType) joins renders with ' | '"):
    forAll(Gen.listOfN(3, genType(1))) { ts =>
      h.renderType(s.UnionType(ts)) == ts.map(h.renderType).mkString(" | ")
    }

  property(
    "renderType unwraps Annotated/Existential/Universal/Structural to the inner rendering, discarding the wrapper"
  ):
    forAll(genType(2), Gen.oneOf(0, 1, 2, 3)) { (t, which) =>
      val wrapped = which match
        case 0 => s.AnnotatedType(Nil, t)
        case 1 => s.ExistentialType(t, None)
        case 2 => s.UniversalType(None, t)
        case _ => s.StructuralType(t, None)
      h.renderType(wrapped) == h.renderType(t)
    }

  property("renderType(ConstantType(c)) delegates to renderConstant(c) for any constant"):
    forAll(genConstant) { c =>
      h.renderType(s.ConstantType(c)) == h.renderConstant(c)
    }

  property("renderConstant(IntConstant) renders as the plain decimal number"):
    forAll(Gen.chooseNum(Int.MinValue, Int.MaxValue)) { v =>
      h.renderConstant(s.IntConstant(v)) == v.toString
    }

  property("renderConstant(LongConstant) renders with an 'L' suffix"):
    forAll(Gen.chooseNum(Long.MinValue, Long.MaxValue)) { v =>
      h.renderConstant(s.LongConstant(v)) == s"${v}L"
    }

  property("renderConstant(FloatConstant) renders with an 'f' suffix"):
    forAll(Gen.chooseNum(Float.MinValue, Float.MaxValue)) { v =>
      h.renderConstant(s.FloatConstant(v)) == s"${v}f"
    }

  property("renderConstant(DoubleConstant) renders as its plain toString"):
    forAll(Gen.chooseNum(Double.MinValue, Double.MaxValue)) { v =>
      h.renderConstant(s.DoubleConstant(v)) == v.toString
    }

  property("renderConstant(BooleanConstant) renders 'true'/'false' for both values"):
    forAll(Gen.oneOf(true, false)) { v =>
      h.renderConstant(s.BooleanConstant(v)) == v.toString
    }

  property("renderConstant(CharConstant) renders as a single-quoted character"):
    forAll(Gen.chooseNum(0, 0xd7ff)) { code =>
      h.renderConstant(s.CharConstant(code)) == s"'${code.toChar}'"
    }

  property("renderConstant(StringConstant) renders as a double-quoted string"):
    forAll(Gen.asciiPrintableStr) { v =>
      h.renderConstant(s.StringConstant(v)) == s"\"$v\""
    }

  property("renderConstant(ShortConstant/ByteConstant) render as the plain decimal number"):
    forAll(
      Gen.chooseNum(Short.MinValue.toInt, Short.MaxValue.toInt),
      Gen.chooseNum(Byte.MinValue.toInt, Byte.MaxValue.toInt)
    ) { (sv, bv) =>
      h.renderConstant(s.ShortConstant(sv)) == sv.toString &&
      h.renderConstant(s.ByteConstant(bv)) == bv.toString
    }

  property("renderConstant(UnitConstant) is always \"Unit\""):
    h.renderConstant(s.UnitConstant()) == "Unit"

  property("renderConstant(NullConstant) is always \"null\""):
    h.renderConstant(s.NullConstant()) == "null"

  property(
    "renderMethod assembles 'def name[tparams](params): ret' exactly, for arbitrary names/arities"
  ):
    forAll(
      PropertyGens.nonEmptyString,
      Gen.listOf(PropertyGens.nonEmptyString),
      Gen.listOf(PropertyGens.parameterList),
      PropertyGens.nonEmptyString
    ) { (name, tparams, plists, ret) =>
      val expectedTp = if tparams.isEmpty then "" else tparams.mkString("[", ", ", "]")
      val expectedPs = plists.map { pl =>
        val prefix = if pl.isImplicit then "implicit " else ""
        pl.parameters.map(p => s"${p.name}: ${p.tpe}").mkString(s"($prefix", ", ", ")")
      }.mkString
      h.renderMethod(name, tparams, plists, ret) == s"def $name$expectedTp$expectedPs: $ret"
    }

  property("renderMethod's parameter list is prefixed with 'implicit ' iff that list is implicit"):
    forAll(Gen.listOf(PropertyGens.parameter), Gen.oneOf(true, false)) { (params, isImplicitList) =>
      val out = h.renderMethod("m", Nil, List(ParameterList(params, isImplicitList)), "R")
      out.contains("(implicit ") == isImplicitList
    }
