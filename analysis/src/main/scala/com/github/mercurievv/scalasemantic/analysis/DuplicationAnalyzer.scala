package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.model.Position as ModelPosition
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.*
import scala.util.Try

object DuplicationAnalyzer:

  def analyze(
      index: SemanticIndex,
      root: Path,
      minSize: Int,
      pathFilter: Option[String] = None
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
              findSubtrees(sourceTree, minSize).iterator.map { case (t, enclosing) =>
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
      val firstTree = g.head._2
      val astNodeCount = nodeCount(firstTree)
      val occurrences = g.map { case (doc, tree, enclosing) =>
        val pos = tree.pos
        val range = Range(
          ModelPosition(pos.startLine, pos.startColumn),
          ModelPosition(pos.endLine, pos.endColumn)
        )
        val location = Location(doc.uri, range)
        val enclosingName = enclosing.map(_.name.value)
        DuplicateOccurrence(location, enclosingName)
      }
      DuplicationGroup(occurrences.size, astNodeCount, occurrences)
    }

    // Sort by AST node count descending, then by number of occurrences descending
    val sortedGroups = groups.sortBy(g => (-g.astNodeCount, -g.size))
    DuplicationsResult(sortedGroups)

  private def nodeCount(tree: Tree): Int =
    1 + tree.children.map(nodeCount).sum

  private def findSubtrees(tree: Tree, minSize: Int): List[(Tree, Option[Defn.Def])] =
    def traverse(t: Tree, currentMethod: Option[Defn.Def]): List[(Tree, Option[Defn.Def])] =
      val nextMethod = t match
        case d: Defn.Def => Some(d)
        case _           => currentMethod

      val current =
        if (nodeCount(t) >= minSize)
          t match
            case _: Term.Block | _: Defn.Def | _: Term.Match | _: Term.For | _: Term.If |
                _: Term.Try =>
              List((t, currentMethod))
            case _ => Nil
        else Nil

      current ++ t.children.flatMap(c => traverse(c, nextMethod))

    traverse(tree, None)

  private def isDescendant(child: Tree, parent: Tree): Boolean =
    if (child eq parent) false
    else parent.children.exists(c => (c eq child) || isDescendant(child, c))

  private def collectLocalNames(tree: Tree): List[String] =
    def loop(t: Tree): List[String] =
      val current = t match
        case Term.Param(_, name, _, _) if name.value.nonEmpty       => List(name.value)
        case Pat.Var(Term.Name(value)) if value.nonEmpty            => List(value)
        case Type.Param(_, name, _, _, _, _) if name.value.nonEmpty => List(name.value)
        case Defn.Def(_, name, _, _, _, _) if name.value.nonEmpty   => List(name.value)
        case Defn.Val(_, pats, _, _) =>
          pats.collect { case Pat.Var(Term.Name(value)) if value.nonEmpty => value }
        case Defn.Var(_, pats, _, _) =>
          pats.collect { case Pat.Var(Term.Name(value)) if value.nonEmpty => value }
        case _ => Nil
      current ++ t.children.flatMap(loop)

    loop(tree).distinct

  private def collectReferencedNames(tree: Tree): List[String] =
    def loop(t: Tree): List[String] =
      t match
        case Term.Name(value) if value.nonEmpty => List(value)
        case other                              => other.children.flatMap(loop)
    loop(tree).distinct

  def normalize(tree: Tree, enclosingMethod: Option[Defn.Def]): String =
    val scopeTree = enclosingMethod.getOrElse(tree)
    val localNames = collectLocalNames(scopeTree)
    val referencedNames = collectReferencedNames(tree)
    val localReferencedNames = referencedNames.filter(localNames.contains)

    val rootName = scopeTree match
      case d: Defn.Def => Some(d.name.value)
      case v: Defn.Val => Some(v.pats.headOption.map(_.syntax).getOrElse("val"))
      case v: Defn.Var => Some(v.pats.headOption.map(_.syntax).getOrElse("var"))
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
