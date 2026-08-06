package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.OutlineEntry

/** Pruning of an already-built outline tree: pure, index-free, and therefore testable without a
  * compiler or a SemanticDB. [[Analyzer.outlineFiltered]] supplies the predicate; everything about
  * *how* the tree is narrowed lives here.
  */
private[analysis] object OutlineFilter:

  /** Keeps the entries satisfying `matches`, bounded by `maxDepth`.
    *
    * With `includeParents`, a non-matching entry survives only as context for a matching
    * descendant, and its children are narrowed to the paths that lead there — so the result reads
    * as `Outer -> Inner -> match` rather than a flat list that loses where the match lives. Without
    * it, each match becomes a root in its own right.
    *
    * `maxDepth` counts levels of the retained subtree from each match (1 = the match alone) and is
    * independent of how deep the match sits in the file, so the bound means the same thing for a
    * top-level type and a nested one. Ancestors kept purely as context do not consume depth.
    */
  def apply(
      entries: List[OutlineEntry],
      matches: OutlineEntry => Boolean,
      includeParents: Boolean,
      maxDepth: Option[Int]
  ): List[OutlineEntry] =
    def truncate(entry: OutlineEntry, depth: Int): OutlineEntry =
      if maxDepth.exists(depth >= _) then entry.copy(children = Nil)
      else entry.copy(children = entry.children.map(truncate(_, depth + 1)))

    def asRoots(entry: OutlineEntry): List[OutlineEntry] =
      if matches(entry) then List(truncate(entry, 1))
      else entry.children.flatMap(asRoots)

    def withContext(entry: OutlineEntry): Option[OutlineEntry] =
      if matches(entry) then Some(truncate(entry, 1))
      else
        entry.children.flatMap(withContext) match
          case Nil      => None
          case retained => Some(entry.copy(children = retained))

    if includeParents then entries.flatMap(withContext)
    else entries.flatMap(asRoots)
