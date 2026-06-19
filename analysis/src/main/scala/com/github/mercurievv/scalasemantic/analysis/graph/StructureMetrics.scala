package com.github.mercurievv.scalasemantic.analysis.graph

import com.github.mercurievv.scalasemantic.analysis.graph.GraphMetrics.Graph
import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Computes the structural metrics for a project: per-type coupling (Ca/Ce/instability), layer
  * (longest-path on the SCC-condensed graph), centrality (PageRank), and cycle membership across
  * the four edge dimensions and a combined overlay, plus a module rollup and the weighted module
  * coupling surface. Cyclic nodes are reported via `inCycle`/`sccSize` and share their cycle's
  * layer, never assigned a faked per-node layer.
  */
final class StructureMetrics(index: SemanticIndex):

  private val graphs = new DependencyGraphs(index)
  private val nodes = graphs.nodes
  private val dimensionNames = List("extends", "memberType", "call", "implicit")

  def result(): StructureResult =
    val perDim = graphs.dimensions.view.mapValues(metricsFor).toMap
    val combined = metricsFor(graphs.combined)

    val symbols = nodes.toList
      .map { n =>
        SymbolStructure(
          symbol = n,
          displayName = index.displayName(n),
          module = graphs.moduleOf(n),
          combined = combined(n),
          perDimension = dimensionNames.map(d => d -> perDim(d)(n)).toMap
        )
      }
      .sortBy(_.symbol)

    val cycles =
      (dimensionNames.map(d => d -> graphs.dimensions(d)) :+ ("combined" -> graphs.combined))
        .flatMap { (name, g) =>
          GraphMetrics
            .stronglyConnectedComponents(nodes, g)
            .filter(_.size > 1)
            .map(c => DependencyCycle(name, c.toList.sorted))
        }

    StructureResult(symbols, moduleRollup, moduleEdges, cycles)

  /** Per-node `DimensionMetrics` for one graph: coupling + layer + centrality + SCC size. */
  private def metricsFor(graph: Graph): Map[String, DimensionMetrics] =
    val coupling = GraphMetrics.coupling(nodes, graph)
    val sccSize = sccSizes(nodes, graph)
    val layer = GraphMetrics.layers(nodes, graph)
    val centrality = GraphMetrics.pageRank(nodes, graph)
    nodes.iterator.map { n =>
      val (ca, ce) = coupling.getOrElse(n, (0, 0))
      val size = sccSize.getOrElse(n, 1)
      n -> DimensionMetrics(
        ca,
        ce,
        GraphMetrics.instability(ca, ce),
        layer.getOrElse(n, 0),
        centrality.getOrElse(n, 0.0),
        size,
        size > 1
      )
    }.toMap

  // The combined dependency graph lifted to modules (a type's module = leading uri segment).
  private val moduleNodes: Set[String] = nodes.map(graphs.moduleOf)
  private val moduleGraph: Graph =
    graphs.combined.toList
      .flatMap((from, tos) => tos.toList.map(to => graphs.moduleOf(from) -> graphs.moduleOf(to)))
      .filter((a, b) => a != b)
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  /** Module-level rollup of the combined graph: coupling, layer, and cycle membership per module.
    */
  private def moduleRollup: List[ModuleStructure] =
    val coupling = GraphMetrics.coupling(moduleNodes, moduleGraph)
    val sccSize = sccSizes(moduleNodes, moduleGraph)
    val layer = GraphMetrics.layers(moduleNodes, moduleGraph)
    val typeCount = nodes.groupBy(graphs.moduleOf).view.mapValues(_.size).toMap
    moduleNodes.toList.sorted.map { m =>
      val (ca, ce) = coupling.getOrElse(m, (0, 0))
      val size = sccSize.getOrElse(m, 1)
      ModuleStructure(
        module = m,
        typeCount = typeCount.getOrElse(m, 0),
        afferent = ca,
        efferent = ce,
        instability = GraphMetrics.instability(ca, ce),
        layer = layer.getOrElse(m, 0),
        sccSize = size,
        inCycle = size > 1
      )
    }

  /** Weighted module → module dependency edges (the coupling/boundary surface). `weight` counts the
    * type edges crossing; `inCycle` flags edges whose endpoints share a module cycle (a violation).
    */
  private def moduleEdges: List[ModuleEdge] =
    val weights = graphs.combined.toList
      .flatMap((from, tos) => tos.toList.map(to => graphs.moduleOf(from) -> graphs.moduleOf(to)))
      .filter((a, b) => a != b)
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap
    val components = GraphMetrics.stronglyConnectedComponents(moduleNodes, moduleGraph)
    val componentOf = components.iterator.zipWithIndex.flatMap((c, i) => c.map(_ -> i)).toMap
    val componentSize = components.flatMap(c => c.map(_ -> c.size)).toMap
    weights.toList.sortBy((pair, _) => pair).map { case ((from, to), weight) =>
      val cyclic =
        componentOf.get(from) == componentOf.get(to) && componentSize.getOrElse(from, 1) > 1
      ModuleEdge(from, to, weight, cyclic)
    }

  private def sccSizes(nodeSet: Set[String], graph: Graph): Map[String, Int] =
    GraphMetrics
      .stronglyConnectedComponents(nodeSet, graph)
      .flatMap(c => c.map(_ -> c.size))
      .toMap
