package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.CallHierarchyNode
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

  // ---------------------------------------------------------------------------
  // callHierarchy: depth bound + cycle-breaking, over arbitrary call graphs
  // ---------------------------------------------------------------------------
  //
  // AnalyzerCoreSuite checks callHierarchy's depth-first expansion, depth limit, and
  // cycle-breaking-to-a-leaf against a handful of hand-built call graphs (a->b->c, a<->b). This
  // generalises all three to an arbitrary node count, edge set, root, depth, and direction. Call
  // edges are attributed by callGraph to "the most recent method DEFINITION in source order", so a
  // graph is encoded directly as that occurrence sequence: a DEFINITION for method i followed by a
  // REFERENCE for each of its callees (self-edges are excluded — a REFERENCE to the
  // currently-open definition is not attributed as an edge, so they cannot be expressed this way).
  //
  // The single recursive check below is an independent restatement of callHierarchy's own contract
  // (depth-limited, per-path cycle-breaking, direct-edge expansion) against the plain `Int` edge
  // map the test built the fixture from — not a call into callHierarchy's own adjacency maps.

  private def methSym(i: Int): String = s"${P}M#m$i()."

  private def callGraphDoc(n: Int, edges: Map[Int, Set[Int]]): s.TextDocument =
    def occ(sym: String, role: s.SymbolOccurrence.Role, line: Int) =
      s.SymbolOccurrence(Some(s.Range(line, 0, line, 1)), sym, role)
    val occs = (0 until n).flatMap { i =>
      occ(methSym(i), s.SymbolOccurrence.Role.DEFINITION, i) +:
        edges
          .getOrElse(i, Set.empty)
          .toList
          .map(j => occ(methSym(j), s.SymbolOccurrence.Role.REFERENCE, i))
    }
    val methods =
      (0 until n).map(i => info(methSym(i), s.SymbolInformation.Kind.METHOD, s"m$i", s.NoSignature))
    s.TextDocument(uri = "ch.scala", symbols = methods.toVector, occurrences = occs.toVector)

  private val genCallGraph: Gen[(Int, Map[Int, Set[Int]])] =
    for
      n <- Gen.choose(1, 5)
      edges <- Gen.sequence[List[Set[Int]], Set[Int]](
        (0 until n).toList.map(i => Gen.someOf((0 until n).filterNot(_ == i)).map(_.toSet))
      )
    yield (n, (0 until n).zip(edges).toMap)

  property(
    "callHierarchy: every node's children equal its direct edges, unless depth-limited or " +
      "already visited on this path (which makes it a leaf) — for any call graph, root, depth, " +
      "and direction"
  ):
    forAll(
      genCallGraph,
      Gen.choose(0, 4),
      Gen.choose(1, 4),
      Gen.oneOf("callees", "callers")
    ) { case ((n, edges), rootOffset, depth, direction) =>
      val rootIdx = rootOffset % n
      val az = Analyzer(SemanticIndex(Vector(callGraphDoc(n, edges))))
      val reverseEdges: Map[Int, Set[Int]] =
        (0 until n)
          .map(j => j -> (0 until n).filter(edges.getOrElse(_, Set.empty).contains(j)).toSet)
          .toMap
      val adjUsed = if direction == "callees" then edges else reverseEdges

      def idxOf(displayName: String): Int = displayName.stripPrefix("m").toInt

      def checkNode(node: CallHierarchyNode, depthSoFar: Int, ancestors: Set[Int]): Boolean =
        val idx = idxOf(node.method.displayName)
        val expectedChildren =
          if ancestors.contains(idx) || depthSoFar >= depth then Set.empty[Int]
          else adjUsed.getOrElse(idx, Set.empty)
        val actualChildren = node.children.map(c => idxOf(c.method.displayName)).toSet
        actualChildren == expectedChildren &&
        node.children.forall(c => checkNode(c, depthSoFar + 1, ancestors + idx))

      val h = az.callHierarchy(method(methSym(rootIdx)), positiveInt(depth), direction)
      checkNode(h.root, 0, Set.empty)
    }

  private def positiveInt(v: Int) = PositiveInt.from(v, "depth").fold(fail(_), identity)
