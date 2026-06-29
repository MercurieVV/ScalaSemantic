package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.model.Position as ModelPosition
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.*
import scala.util.Try

object DuplicationAnalyzer:

  /** The default set of AST node types considered as duplication candidates.
    *
    * Previously this predicate was inlined inside `findSubtrees`; naming it makes the choice
    * visible and lets callers supply a narrower or wider predicate via the `nodeFilter` parameter
    * of `analyze`.
    */
  val defaultCandidateNodeFilter: Tree => Boolean = {
    case _: Term.Block | _: Defn.Def | _: Term.Match | _: Term.For | _: Term.If | _: Term.Try =>
      true
    case _ => false
  }

  /** The default strategy for identifying the "enclosing declaration" of a sub-tree.
    *
    * Only method definitions (`Defn.Def`) are tracked by default. Callers can supply a wider
    * matcher — for example to also include `Defn.Object`, `Defn.Class`, or `Defn.Trait` — via the
    * `enclosingDeclMatcher` parameter of `analyze`, without touching the core traversal logic.
    */
  val defaultEnclosingDeclMatcher: Tree => Option[Defn] = {
    case d: Defn.Def => Some(d)
    case _           => None
  }

  def analyze(
      index: SemanticIndex,
      root: Path,
      minSize: Int,
      pathFilter: Option[String] = None,
      nodeFilter: Tree => Boolean = defaultCandidateNodeFilter,
      enclosingDeclMatcher: Tree => Option[Defn] = defaultEnclosingDeclMatcher
  ): DuplicationsResult =
    val h = AnalyzerHelpers(index)
    val keepUri = h.globMatcher(pathFilter.filter(_.nonEmpty))

    // 1. Parse all files in index that match the path filter
    val candidateSubtrees = index.documents.iterator
      .filter(doc => keepUri(doc.uri))
      .flatMap { doc =>
        val text =
          if (doc.text.nonEmpty) doc.text
          else {
            val p = root.resolve(doc.uri)
            if (Files.exists(p))
              new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8)
            else ""
          }
        if (text.isEmpty) Iterator.empty
        else
          Try(dialects.Scala3(text).parse[Source].get).toOption match
            case Some(sourceTree) =>
              findSubtrees(sourceTree, minSize, nodeFilter, enclosingDeclMatcher).iterator
                .map { case (t, enclosing) =>
                  (doc, t, enclosing)
                }
            case None =>
              Iterator.empty
      }
      .toList

    // 2. Group candidate subtrees by their normalized structure
    val grouped = candidateSubtrees.groupBy { case (_, tree, enclosing) =>
      normalize(tree, enclosing)
    }

    // 3. Filter groups with size > 1 (i.e. duplicates exist)
    val duplicateGroups = grouped.values.iterator
      .filter(_.size > 1)
      .map(_.map { case (doc, tree, enclosing) => (doc, tree, enclosing) })
      .toList

    // 4. Filter out groups that are completely subsumed by another larger group
    val filteredGroups = duplicateGroups.filter { g1 =>
      val trees1 = g1.map(_._2)
      !duplicateGroups.exists { g2 =>
        val trees2 = g2.map(_._2)
        (g1 ne g2) && trees1.forall { t1 =>
          trees2.exists(t2 => isDescendant(t1, t2))
        }
      }
    }

    // 5. Build DuplicationGroups
    val groups = filteredGroups.map { g =>
      val astNodeCount = g.map(_._2).headOption.fold(0)(nodeCount)
      val occurrences = g.map { case (doc, tree, enclosing) =>
        val pos = tree.pos
        val range = Range(
          ModelPosition(pos.startLine, pos.startColumn),
          ModelPosition(pos.endLine, pos.endColumn)
        )
        val location = Location(doc.uri, range)
        val enclosingName = enclosing.flatMap(enclosingDeclName)
        DuplicateOccurrence(location, enclosingName)
      }
      DuplicationGroup(occurrences.size, astNodeCount, occurrences)
    }

    // Sort by AST node count descending, then by number of occurrences descending
    val sortedGroups = groups.sortBy(g => (-g.astNodeCount, -g.size))
    DuplicationsResult(sortedGroups)

  /** Extract a display name from an enclosing declaration node.
    *
    * Handles the most common declaration kinds that carry a single identifier name. Returns `None`
    * for declarations without a simple name (e.g. anonymous givens, pattern vals with complex
    * patterns).
    */
  private def enclosingDeclName(defn: Defn): Option[String] = defn match
    case d: Defn.Def    => Some(d.name.value).filter(_.nonEmpty)
    case d: Defn.Object => Some(d.name.value).filter(_.nonEmpty)
    case d: Defn.Class  => Some(d.name.value).filter(_.nonEmpty)
    case d: Defn.Trait  => Some(d.name.value).filter(_.nonEmpty)
    case d: Defn.Val =>
      d.pats.headOption.collect { case Pat.Var(n) => n.value }.filter(_.nonEmpty)
    case d: Defn.Var =>
      d.pats.headOption.collect { case Pat.Var(n) => n.value }.filter(_.nonEmpty)
    case _ => None

  private def nodeCount(tree: Tree): Int =
    1 + tree.children.map(nodeCount).sum

  /** Collect all sub-trees of `tree` with at least `minSize` AST nodes that pass `nodeFilter`.
    *
    * @param nodeFilter
    *   Predicate controlling which node kinds are eligible candidates. Defaults to
    *   [[defaultCandidateNodeFilter]].
    * @param enclosingDeclMatcher
    *   Returns `Some(defn)` when a tree node should be tracked as the new enclosing declaration.
    *   Defaults to [[defaultEnclosingDeclMatcher]] (tracks only `Defn.Def`).
    */
  private def findSubtrees(
      tree: Tree,
      minSize: Int,
      nodeFilter: Tree => Boolean,
      enclosingDeclMatcher: Tree => Option[Defn]
  ): List[(Tree, Option[Defn])] =
    def traverse(t: Tree, currentEnclosing: Option[Defn]): List[(Tree, Option[Defn])] =
      val nextEnclosing = enclosingDeclMatcher(t) orElse currentEnclosing

      val current =
        if (nodeCount(t) >= minSize && nodeFilter(t)) List((t, currentEnclosing))
        else Nil

      current ++ t.children.flatMap(c => traverse(c, nextEnclosing))

    traverse(tree, None)

  private def isDescendant(child: Tree, parent: Tree): Boolean =
    if (child eq parent) false
    else parent.children.exists(c => (c eq child) || isDescendant(child, c))

  private def collectLocalNames(tree: Tree): List[String] =
    def loop(t: Tree): List[String] =
      val current = t match
        case Term.Param(_, name, _, _) if name.value.nonEmpty => List(name.value)
        case Pat.Var(Term.Name(value)) if value.nonEmpty      => List(value)
        case param: Type.Param if param.name.value.nonEmpty   => List(param.name.value)
        case defn: Defn.Def if defn.name.value.nonEmpty       => List(defn.name.value)
        case Defn.Val(_, pats, _, _) =>
          pats.collect { case Pat.Var(Term.Name(value)) if value.nonEmpty => value }
        case defn: Defn.Var =>
          defn.pats.collect { case Pat.Var(Term.Name(value)) if value.nonEmpty => value }
        case _ => Nil
      current ++ t.children.flatMap(loop)

    loop(tree).distinct

  private def collectReferencedNames(tree: Tree): List[String] =
    def loop(t: Tree): List[String] =
      t match
        case Term.Name(value) if value.nonEmpty => List(value)
        case other                              => other.children.flatMap(loop)
    loop(tree).distinct

  /** Produce a structural fingerprint for `tree` that is invariant under local-name renaming.
    *
    * @param enclosingMethod
    *   The enclosing declaration whose local names should be replaced with stable placeholders.
    *   Accepts any [[Defn]] (was previously restricted to [[Defn.Def]]).
    */
  def normalize(tree: Tree, enclosingMethod: Option[Defn]): String =
    val scopeTree = enclosingMethod.getOrElse(tree)
    val localNames = collectLocalNames(scopeTree)
    val referencedNames = collectReferencedNames(tree)
    val localReferencedNames = referencedNames.filter(localNames.contains)

    val rootName = scopeTree match
      case d: Defn.Def => Some(d.name.value)
      case v: Defn.Val => v.pats.headOption.map(_.syntax)
      case v: Defn.Var => v.pats.headOption.map(_.syntax)
      case _           => None

    val localReferencedNamesNoRoot = localReferencedNames.filter(n => !rootName.contains(n))
    val placeholders = localReferencedNamesNoRoot.zipWithIndex.map { case (name, idx) =>
      name -> s"var$idx"
    }.toMap
    val nameToPlaceholder = placeholders ++ rootName.map(_ -> "root")

    def buildStructure(t: Tree): String =
      t match
        case Term.Name(value) =>
          val nameVal = nameToPlaceholder.getOrElse(value, value)
          s"Term.Name($nameVal)"
        case _: Lit =>
          "Lit"
        case _: Type =>
          "Type"
        case other =>
          val children = other.children.map(buildStructure).mkString(",")
          s"${other.productPrefix}($children)"

    buildStructure(tree)
