package com.github.mercurievv.scalasemantic.analysis.graph

import com.github.mercurievv.scalasemantic.analysis.graph.GraphMetrics.Graph
import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Computes the Phase-1 structural metrics for a project: per-type coupling (Ca/Ce/instability) and
  * cycle membership (SCC) across the four edge dimensions and a combined overlay, plus a
  * module-level rollup. No layering — cyclic nodes are reported via `inCycle`, not assigned a faked
  * layer.
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

    StructureResult(symbols, moduleRollup, cycles)

  /** Per-node `DimensionMetrics` for one graph: coupling + layer + SCC size. */
  private def metricsFor(graph: Graph): Map[String, DimensionMetrics] =
    val coupling = GraphMetrics.coupling(nodes, graph)
    val sccSize = sccSizes(nodes, graph)
    val layer = GraphMetrics.layers(nodes, graph)
    nodes.iterator.map { n =>
      val (ca, ce) = coupling.getOrElse(n, (0, 0))
      val size = sccSize.getOrElse(n, 1)
      n -> DimensionMetrics(
        ca,
        ce,
        GraphMetrics.instability(ca, ce),
        layer.getOrElse(n, 0),
        size,
        size > 1
      )
    }.toMap

  /** Module-level rollup of the combined graph: each type's module, edges lifted to module pairs.
    */
  private def moduleRollup: List[ModuleStructure] =
    val moduleNodes = nodes.map(graphs.moduleOf)
    val moduleGraph: Graph =
      graphs.combined.toList
        .flatMap((from, tos) => tos.toList.map(to => graphs.moduleOf(from) -> graphs.moduleOf(to)))
        .filter((a, b) => a != b)
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap
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

  private def sccSizes(nodeSet: Set[String], graph: Graph): Map[String, Int] =
    GraphMetrics
      .stronglyConnectedComponents(nodeSet, graph)
      .flatMap(c => c.map(_ -> c.size))
      .toMap
