package com.github.mercurievv.scalasemantic.analysis.graph

/** Pure graph algorithms over a directed graph `node -> out-neighbours`, restricted to a fixed node
  * set. Written functionally (immutable accumulators, no mutation) to satisfy the project's
  * wartremover rules; recursion depth is bounded by the graph's depth, fine for project-sized
  * graphs.
  */
object GraphMetrics:

  /** A directed graph: each node maps to the set of nodes it depends on. Edges point to nodes only.
    */
  type Graph = Map[String, Set[String]]

  /** Afferent (Ca, fan-in) and efferent (Ce, fan-out) coupling for every node. */
  def coupling(nodes: Set[String], graph: Graph): Map[String, (Int, Int)] =
    val incoming: Map[String, Int] =
      graph.toList
        .flatMap((_, outs) => outs.toList)
        .filter(nodes.contains)
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap
    nodes.iterator
      .map(n => n -> (incoming.getOrElse(n, 0), graph.getOrElse(n, Set.empty).size))
      .toMap

  /** Instability Ce/(Ca+Ce), or 0 for an isolated node (no edges either way). */
  def instability(ca: Int, ce: Int): Double =
    if ca + ce == 0 then 0.0 else ce.toDouble / (ca + ce)

  /** Strongly-connected components (Kosaraju): a list of node sets. Singleton sets are acyclic
    * nodes; a set of size > 1 is a dependency cycle (mutually-reachable nodes).
    */
  def stronglyConnectedComponents(nodes: Set[String], graph: Graph): List[Set[String]] =
    val order = finishOrder(nodes, graph) // highest finish time first
    val reversed = reverse(nodes, graph)
    order
      .foldLeft((Set.empty[String], List.empty[Set[String]])) { case ((assigned, comps), n) =>
        if assigned.contains(n) then (assigned, comps)
        else
          val comp = collect(n, reversed, assigned, Set.empty)
          (assigned ++ comp, comp :: comps)
      }
      ._2

  /** Post-order DFS over all nodes, accumulating finish order with the last-finished node first. */
  private def finishOrder(nodes: Set[String], graph: Graph): List[String] =
    def dfs(n: String, visited: Set[String], acc: List[String]): (Set[String], List[String]) =
      if visited.contains(n) then (visited, acc)
      else
        val (v, a) = graph
          .getOrElse(n, Set.empty)
          .foldLeft((visited + n, acc)) { case ((vv, aa), m) => dfs(m, vv, aa) }
        (v, n :: a)
    nodes.toList
      .foldLeft((Set.empty[String], List.empty[String])) { case ((v, a), n) => dfs(n, v, a) }
      ._2

  /** All nodes reachable from `start` in `graph`, skipping already-assigned nodes — one SCC. */
  private def collect(
      start: String,
      graph: Graph,
      assigned: Set[String],
      acc: Set[String]
  ): Set[String] =
    if assigned.contains(start) || acc.contains(start) then acc
    else
      graph
        .getOrElse(start, Set.empty)
        .foldLeft(acc + start) { (a, m) => collect(m, graph, assigned, a) }

  private def reverse(nodes: Set[String], graph: Graph): Graph =
    val empty = nodes.iterator.map(_ -> Set.empty[String]).toMap
    graph.foldLeft(empty) { case (acc, (from, outs)) =>
      outs.foldLeft(acc) { (a, to) =>
        if nodes.contains(to) then a.updated(to, a.getOrElse(to, Set.empty) + from) else a
      }
    }
