package scalasemantic.analysis

import scalasemantic.model.*
import scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** The semantic query engine: turns a [[SemanticIndex]] into the result models that back the MCP
  * tools. Phase 3 covers find-usages, method-signature, and class-hierarchy.
  */
final class Analyzer(index: SemanticIndex):

  // --- find-usages ----------------------------------------------------------

  /** Every occurrence of `symbol` across all indexed documents, split into definitions and
    * references. This is inherently cross-file: occurrences are scanned over the whole index.
    */
  def findUsages(symbol: String): UsagesResult =
    val located = index.occurrences.collect {
      case (uri, occ) if occ.symbol == symbol =>
        occ.role -> location(uri, occ.range)
    }
    val defs = located.collect { case (s.SymbolOccurrence.Role.DEFINITION, loc) => loc }.toList
    val refs = located.collect { case (s.SymbolOccurrence.Role.REFERENCE, loc) => loc }.toList
    UsagesResult(symbol, index.displayName(symbol), defs, refs)

  // --- method-signature -----------------------------------------------------

  /** Full method signature including type parameters and (implicit) parameter lists. */
  def methodSignature(symbol: String): Option[MethodSignature] =
    index.info(symbol).map(_.signature).collect { case m: s.MethodSignature =>
      val name = index.displayName(symbol)
      val tparams = scopeInfos(m.typeParameters).map(_.displayName).toList
      val plists = m.parameterLists.map { scope =>
        val params = scopeInfos(Some(scope)).map { p =>
          Parameter(p.displayName, renderType(valueType(p)), isImplicit(p))
        }.toList
        ParameterList(params, params.nonEmpty && params.forall(_.isImplicit))
      }.toList
      val ret = renderType(m.returnType)
      MethodSignature(symbol, name, tparams, plists, ret, renderMethod(name, tparams, plists, ret))
    }

  // --- class-hierarchy ------------------------------------------------------

  /** Parents, transitive linearization, and known subtypes (the latter is something Metals cannot
    * answer directly — it requires scanning every type in the index).
    */
  def classHierarchy(symbol: String): Option[ClassHierarchy] =
    index.info(symbol).map(_.signature).collect { case c: s.ClassSignature =>
      val parents = c.parents.flatMap(parentSymbol).map(symbolRef).toList
      ClassHierarchy(
        symbol,
        index.displayName(symbol),
        parents,
        linearize(symbol).map(symbolRef),
        knownSubtypes(symbol).map(symbolRef)
      )
    }

  /** Direct parent symbols declared by a type's `ClassSignature` (empty for non-classes). */
  private def directParents(info: s.SymbolInformation): List[String] =
    info.signature match
      case c: s.ClassSignature => c.parents.flatMap(parentSymbol).toList
      case _                   => Nil

  /** Depth-first transitive parents (excluding `symbol` itself), de-duplicated by first sight. */
  private def linearize(symbol: String): List[String] =
    def parentsOf(sym: String): List[String] = index.info(sym).map(directParents).getOrElse(Nil)
    def loop(queue: List[String], seen: List[String]): List[String] =
      queue match
        case Nil => seen
        case head :: tail =>
          if seen.contains(head) then loop(tail, seen)
          else loop(parentsOf(head) ::: tail, seen :+ head)
    loop(parentsOf(symbol), Nil)

  /** All indexed classes/traits that declare `symbol` among their direct parents. */
  private def knownSubtypes(symbol: String): List[String] =
    index.symbols.values
      .collect {
        case si if directParents(si).contains(symbol) => si.symbol
      }
      .toList
      .sorted

  // --- shared helpers -------------------------------------------------------

  private def location(uri: String, range: Option[s.Range]): Location =
    val r = range.getOrElse(s.Range.defaultInstance)
    Location(
      uri,
      Range(Position(r.startLine, r.startCharacter), Position(r.endLine, r.endCharacter))
    )

  private def symbolRef(symbol: String): SymbolRef =
    SymbolRef(symbol, index.displayName(symbol), kindName(symbol))

  private def kindName(symbol: String): String =
    index.info(symbol).map(_.kind.toString).getOrElse("UNKNOWN")

  private def parentSymbol(tpe: s.Type): Option[String] =
    tpe match
      case s.TypeRef(_, sym, _) => Some(sym)
      case s.SingleType(_, sym) => Some(sym)
      case _                    => None

  private def scopeInfos(scope: Option[s.Scope]): Seq[s.SymbolInformation] =
    scope.toSeq.flatMap { sc =>
      if sc.hardlinks.nonEmpty then sc.hardlinks
      else sc.symlinks.flatMap(index.info)
    }

  private def valueType(info: s.SymbolInformation): s.Type =
    info.signature match
      case v: s.ValueSignature  => v.tpe
      case m: s.MethodSignature => m.returnType
      case _                    => s.Type.Empty

  private def isImplicit(info: s.SymbolInformation): Boolean =
    (info.properties & s.SymbolInformation.Property.IMPLICIT.value) != 0

  private def renderMethod(
      name: String,
      tparams: List[String],
      plists: List[ParameterList],
      ret: String
  ): String =
    val tp = if tparams.isEmpty then "" else tparams.mkString("[", ", ", "]")
    val ps = plists.map { pl =>
      val prefix = if pl.isImplicit then "implicit " else ""
      pl.parameters.map(p => s"${p.name}: ${p.tpe}").mkString(s"($prefix", ", ", ")")
    }.mkString
    s"def $name$tp$ps: $ret"

  /** Best-effort rendering of a SemanticDB type to readable Scala-ish text. */
  private def renderType(tpe: s.Type): String =
    tpe match
      case s.TypeRef(_, sym, args) =>
        val base = index.displayName(sym)
        if args.isEmpty then base else args.map(renderType).mkString(s"$base[", ", ", "]")
      case s.SingleType(_, sym)    => s"${index.displayName(sym)}.type"
      case s.ThisType(sym)         => s"${index.displayName(sym)}.this"
      case s.SuperType(_, sym)     => index.displayName(sym)
      case s.ByNameType(t)         => s"=> ${renderType(t)}"
      case s.RepeatedType(t)       => s"${renderType(t)}*"
      case s.WithType(ts)          => ts.map(renderType).mkString(" with ")
      case s.IntersectionType(ts)  => ts.map(renderType).mkString(" & ")
      case s.UnionType(ts)         => ts.map(renderType).mkString(" | ")
      case s.AnnotatedType(_, t)   => renderType(t)
      case s.ExistentialType(t, _) => renderType(t)
      case s.UniversalType(_, t)   => renderType(t)
      case s.StructuralType(t, _)  => renderType(t)
      case _                       => ""
