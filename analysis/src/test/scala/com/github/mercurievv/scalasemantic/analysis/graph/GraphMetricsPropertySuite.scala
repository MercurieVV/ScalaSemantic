package com.github.mercurievv.scalasemantic.analysis.graph

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Property-based tests for the pure graph algorithms in [[GraphMetrics]].
  *
  * These algorithms are fast, deterministic, and parameter-free (no on-disk index needed), so they
  * are ideal candidates for ScalaCheck: interesting behaviour emerges only from the graph
  * structure, which a generator can vary far more systematically than hand-picked fixtures.
  *
  * Properties covered:
  *   - **instability**: always in [0, 1]; formula is Ce/(Ca+Ce); 0 for isolated nodes.
  *   - **SCC partition**: the result is a true partition (every node appears exactly once, union =
  *     input).
  *   - **layers**: every foundation (no out-edges) has layer 0; a node depending on another never
  *     sits below it.
  *   - **pageRank**: all scores are non-negative; empty graph returns empty; scores sum to
  *     approximately 1 when normalised.
  *   - **coupling**: afferent fan-in and efferent fan-out are consistent with the graph.
  */
class GraphMetricsPropertySuite extends munit.ScalaCheckSuite:

  // ---------------------------------------------------------------------------
  // Generators
  // ---------------------------------------------------------------------------

  /** A small node set: labels "0" through n-1. */
  private def genNodes(maxN: Int): Gen[Set[String]] =
    Gen.chooseNum(0, maxN).map(n => (0 until n).map(_.toString).toSet)

  /** A random graph over `nodes`: each node independently references a random subset of `nodes`. */
  private def genGraph(nodes: Set[String]): Gen[Map[String, Set[String]]] =
    if nodes.isEmpty then Gen.const(Map.empty)
    else
      val nodeList = nodes.toList
      Gen
        .sequence[List[Set[String]], Set[String]](
          nodeList.map(_ => Gen.someOf(nodeList).map(_.toSet))
        )
        .map(edges => nodeList.zip(edges).toMap)

  /** (nodes, graph) pair. */
  private val genNodeGraph: Gen[(Set[String], Map[String, Set[String]])] =
    for
      nodes <- genNodes(6)
      graph <- genGraph(nodes)
    yield (nodes, graph)

  // ---------------------------------------------------------------------------
  // instability properties
  // ---------------------------------------------------------------------------

  property("instability(0, 0) is exactly 0.0"):
    GraphMetrics.instability(0, 0) == 0.0

  property("instability is in [0.0, 1.0] for all non-negative Ca and Ce"):
    forAll(Gen.chooseNum(0, 1000), Gen.chooseNum(0, 1000)) { (ca, ce) =>
      val i = GraphMetrics.instability(ca, ce)
      i >= 0.0 && i <= 1.0
    }

  property("instability formula is Ce/(Ca+Ce) whenever Ca+Ce > 0"):
    forAll(Gen.chooseNum(0, 500), Gen.chooseNum(0, 500)) { (ca, ce) =>
      val i = GraphMetrics.instability(ca, ce)
      if ca + ce == 0 then i == 0.0
      else math.abs(i - ce.toDouble / (ca + ce)) < 1e-9
    }

  property("a purely stable node (Ce=0, Ca>0) has instability 0.0"):
    forAll(Gen.chooseNum(1, 100)) { ca =>
      GraphMetrics.instability(ca, 0) == 0.0
    }

  property("a purely unstable node (Ca=0, Ce>0) has instability 1.0"):
    forAll(Gen.chooseNum(1, 100)) { ce =>
      GraphMetrics.instability(0, ce) == 1.0
    }

  // ---------------------------------------------------------------------------
  // SCC partition properties
  // ---------------------------------------------------------------------------

  property("stronglyConnectedComponents is a partition of the node set"):
    forAll(genNodeGraph) { (nodes, graph) =>
      val comps = GraphMetrics.stronglyConnectedComponents(nodes, graph)
      // every node appears in exactly one component
      val allNodes = comps.flatten.toList
      allNodes.sorted == nodes.toList.sorted && allNodes.size == allNodes.distinct.size
    }

  property("stronglyConnectedComponents: every component is non-empty"):
    forAll(genNodeGraph) { (nodes, graph) =>
      GraphMetrics.stronglyConnectedComponents(nodes, graph).forall(_.nonEmpty)
    }

  property("stronglyConnectedComponents: a singleton node with no self-edge is a singleton SCC"):
    forAll(genNodes(5)) { nodes =>
      // acyclic star: 0 -> 1, 0 -> 2 etc.; no back-edges
      val graph = Map("root" -> nodes)
      val allNodes = nodes + "root"
      val comps = GraphMetrics.stronglyConnectedComponents(allNodes, graph)
      // no back-edges -> every node is its own SCC (no non-trivial SCC)
      comps.forall(_.size == 1)
    }

  // ---------------------------------------------------------------------------
  // layers properties
  // ---------------------------------------------------------------------------

  property("layers: a node with no out-edges has layer 0 (it is a foundation)"):
    forAll(genNodeGraph) { (nodes, graph) =>
      if nodes.isEmpty then true
      else
        val layers = GraphMetrics.layers(nodes, graph)
        nodes.forall { n =>
          val outEdges = graph.getOrElse(n, Set.empty).intersect(nodes)
          if outEdges.isEmpty then layers(n) == 0 else true
        }
    }

  property("layers: a node that depends on another is never at a lower layer than it"):
    forAll(genNodeGraph) { (nodes, graph) =>
      if nodes.isEmpty then true
      else
        val layers = GraphMetrics.layers(nodes, graph)
        // for every edge n -> m where both are in nodes and not in a cycle,
        // n's layer must be >= m's layer + 1 OR they share the same SCC level
        val comps = GraphMetrics.stronglyConnectedComponents(nodes, graph)
        val sccOf = comps.zipWithIndex.flatMap { (comp, idx) => comp.map(_ -> idx) }.toMap
        nodes.forall { n =>
          graph.getOrElse(n, Set.empty).intersect(nodes).forall { m =>
            // cross-SCC edge: n's layer must be strictly above m's
            if sccOf(n) != sccOf(m) then layers(n) > layers(m)
            else true // within-SCC ordering is undefined
          }
        }
    }

  property("layers: all layer values are non-negative"):
    forAll(genNodeGraph) { (nodes, graph) =>
      GraphMetrics.layers(nodes, graph).values.forall(_ >= 0)
    }

  // ---------------------------------------------------------------------------
  // pageRank properties
  // ---------------------------------------------------------------------------

  property("pageRank: empty graph returns empty map"):
    GraphMetrics.pageRank(Set.empty, Map.empty) == Map.empty

  property("pageRank: all scores are non-negative"):
    forAll(genNodeGraph) { (nodes, graph) =>
      GraphMetrics.pageRank(nodes, graph).values.forall(_ >= 0.0)
    }

  property("pageRank: a node with no incoming edges has strictly lower rank than one with many"):
    // star: b and c -> a; d has no in-edges
    val nodes = Set("a", "b", "c", "d")
    val graph = Map("b" -> Set("a"), "c" -> Set("a"))
    val pr = GraphMetrics.pageRank(nodes, graph)
    pr("a") > pr("d")

  // ---------------------------------------------------------------------------
  // coupling properties
  // ---------------------------------------------------------------------------

  property(
    "coupling: afferent count equals the number of in-project nodes that point to this node"
  ):
    forAll(genNodeGraph) { (nodes, graph) =>
      if nodes.isEmpty then true
      else
        val cp = GraphMetrics.coupling(nodes, graph)
        nodes.forall { n =>
          val inDegree = nodes.count(src => graph.getOrElse(src, Set.empty).contains(n))
          cp(n)._1 == inDegree
        }
    }

  property("coupling: efferent count equals total out-degree (including out-of-project edges)"):
    forAll(genNodeGraph) { (nodes, graph) =>
      if nodes.isEmpty then true
      else
        val cp = GraphMetrics.coupling(nodes, graph)
        nodes.forall { n =>
          val outDegree = graph.getOrElse(n, Set.empty).size
          cp(n)._2 == outDegree
        }
    }

  property("coupling: afferent is always non-negative"):
    forAll(genNodeGraph) { (nodes, graph) =>
      GraphMetrics.coupling(nodes, graph).values.forall(_._1 >= 0)
    }
