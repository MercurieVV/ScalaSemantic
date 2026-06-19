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

  /** Longest-path layering on the SCC-condensed DAG: each node's level = the length of the longest
    * dependency chain beneath it (0 = a foundation that depends on nothing in-project; higher =
    * depends on deeper chains). Cycles are condensed first, so every member of a cycle shares its
    * component's level — the level is honest (where the tangle sits), but members cannot be ordered
    * *within* the cycle (callers should read `inCycle` to know the intra-cycle order is undefined).
    */
  def layers(nodes: Set[String], graph: Graph): Map[String, Int] =
    val components = stronglyConnectedComponents(nodes, graph)
    val componentOf: Map[String, Int] =
      components.iterator.zipWithIndex.flatMap((c, i) => c.iterator.map(_ -> i)).toMap
    // Condensed DAG: component -> the other components it depends on.
    val condensed: Map[Int, Set[Int]] =
      graph.toList
        .flatMap((from, outs) => outs.toList.filter(nodes.contains).map(to => from -> to))
        .flatMap((from, to) =>
          val ci = componentOf(from)
          val cj = componentOf(to)
          if ci != cj then List(ci -> cj) else Nil
        )
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap
    // Longest path to a sink, memoised over the (acyclic) condensed graph.
    def level(c: Int, memo: Map[Int, Int]): (Int, Map[Int, Int]) =
      memo.get(c) match
        case Some(v) => (v, memo)
        case None =>
          val (lv, m) =
            condensed.getOrElse(c, Set.empty).foldLeft((0, memo)) { case ((acc, mm), s) =>
              val (sv, mm2) = level(s, mm)
              (math.max(acc, sv + 1), mm2)
            }
          (lv, m + (c -> lv))
    val componentLevels = components.indices.foldLeft(Map.empty[Int, Int]) { (memo, c) =>
      level(c, memo)._2
    }
    nodes.iterator.map(n => n -> componentLevels.getOrElse(componentOf(n), 0)).toMap

  /** PageRank importance, where rank flows along dependency edges so a node depended on by many
    * (important) nodes scores high — i.e. "what is central / worth understanding first". Power
    * iteration on the `depends-on` graph; dangling nodes (depend on nothing) simply accumulate
    * rank, which is the desired behaviour for foundations. Scores are unnormalised (relative order
    * is what matters).
    */
  def pageRank(
      nodes: Set[String],
      graph: Graph,
      damping: Double = 0.85,
      iterations: Int = 40
  ): Map[String, Double] =
    val n = nodes.size
    if n == 0 then Map.empty
    else
      val base = (1.0 - damping) / n
      val outDegree =
        nodes.iterator.map(x => x -> graph.getOrElse(x, Set.empty).count(nodes.contains)).toMap
      val dependents = reverse(nodes, graph) // dependents(node) = nodes that depend on it
      val init = nodes.iterator.map(_ -> 1.0 / n).toMap
      (0 until iterations).foldLeft(init) { (rank, _) =>
        nodes.iterator.map { node =>
          val inflow = dependents
            .getOrElse(node, Set.empty)
            .iterator
            .map(m => if outDegree.getOrElse(m, 0) == 0 then 0.0 else rank(m) / outDegree(m))
            .sum
          node -> (base + damping * inflow)
        }.toMap
      }

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
