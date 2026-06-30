package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import stainless.annotation.pure

import scala.meta.internal.semanticdb as s

/** Stateless query helpers over a [[SemanticIndex]]: type/signature rendering, symbol/scope and
  * package lookups, glob/path predicates, range arithmetic, local-binding rendering, and the
  * implicit-resolution primitives. These carry no per-analysis state — they only read the index —
  * so [[Analyzer]] delegates to a single instance, keeping the tool methods (the "what") separate
  * from these utilities (the "how").
  */
private[analysis] final class AnalyzerHelpers(index: SemanticIndex):

  // --- document outline -----------------------------------------------------

  val outlineKinds: Set[s.SymbolInformation.Kind] = Set(
    s.SymbolInformation.Kind.CLASS,
    s.SymbolInformation.Kind.TRAIT,
    s.SymbolInformation.Kind.INTERFACE,
    s.SymbolInformation.Kind.OBJECT,
    s.SymbolInformation.Kind.METHOD,
    s.SymbolInformation.Kind.MACRO,
    s.SymbolInformation.Kind.TYPE,
    s.SymbolInformation.Kind.FIELD
  )

  def includeInOutline(symbol: String): Boolean =
    index.info(symbol).exists { si =>
      outlineKinds.contains(si.kind) && si.displayName.nonEmpty && si.displayName != "<init>"
    }

  // --- annotated source -----------------------------------------------------

  /** An implicit-insertion or inferred-type-argument annotation for one synthetic, anchored at the
    * synthetic's source range. The `kind` distinguishes notes whose range is a precise call site
    * (`inferred-type-args`, `implicit-conversion`) from the using-argument note, whose range is the
    * zero-width enclosing point and so carries no trustworthy column. `None` for synthetics we do
    * not surface.
    */
  def syntheticAnnotation(syn: s.Synthetic): Option[SourceAnnotation] =
    val r = syn.range.getOrElse(s.Range.defaultInstance)
    syn.tree match
      case t: s.TypeApplyTree if t.typeArguments.nonEmpty =>
        Some(
          SourceAnnotation(
            r.startLine,
            r.startCharacter,
            "inferred-type-args",
            t.typeArguments.map(renderType).mkString("[", ", ", "]")
          )
        )
      case app: s.ApplyTree =>
        app.function match
          // using-args appended to a visible call: the function IS the original expression, so the
          // range is the enclosing point, not where the args belong — no reliable column.
          case _: s.OriginalTree =>
            val args = app.arguments.iterator.flatMap(insertedName).toList
            Option.when(args.nonEmpty)(
              SourceAnnotation(
                r.startLine,
                r.startCharacter,
                "implicit",
                args.mkString("(using ", ", ", ")")
              )
            )
          // an implicit conversion wraps the original expression: the range pins the converted
          // expression, so the column is meaningful.
          case fn =>
            insertedName(fn).map(c =>
              SourceAnnotation(r.startLine, r.startCharacter, "implicit-conversion", s"$c(…)")
            )
      case _ => None

  /** Best-effort display name of an inserted implicit tree (a given/implicit reference). */
  def insertedName(tree: s.Tree): Option[String] =
    tree match
      case t: s.IdTree        => Some(index.displayName(t.symbol))
      case t: s.SelectTree    => t.id.flatMap(insertedName)
      case t: s.TypeApplyTree => insertedName(t.function)
      case t: s.ApplyTree     => insertedName(t.function)
      case _                  => None

  val annotatedDefKinds: Set[s.SymbolInformation.Kind] =
    Set(s.SymbolInformation.Kind.METHOD, s.SymbolInformation.Kind.FIELD)

  /** The inferred result/value type of a definition occurrence, as a `: T` note — but only when the
    * source line did not already ascribe a type (so explicitly-typed definitions stay un-noted).
    */
  def defTypeAnnotation(
      occ: s.SymbolOccurrence,
      sourceLines: IndexedSeq[String]
  ): Option[SourceAnnotation] =
    if occ.role != s.SymbolOccurrence.Role.DEFINITION then None
    else
      index
        .info(occ.symbol)
        .filter(si => annotatedDefKinds.contains(si.kind) && si.displayName != "<init>")
        .flatMap { si =>
          val tpe = si.signature match
            case m: s.MethodSignature => m.returnType
            case v: s.ValueSignature  => v.tpe
            case _                    => s.Type.Empty
          val rendered = renderType(tpe)
          val r = occ.range.getOrElse(s.Range.defaultInstance)
          val line = sourceLines.lift(r.startLine).getOrElse("")
          if rendered.isEmpty || alreadyAscribed(line, r.startCharacter) then None
          else
            Some(SourceAnnotation(r.startLine, r.startCharacter, "inferred-type", s": $rendered"))
        }

  /** Whether the definition whose name starts at `nameStart` on `line` already has an explicit type
    * ascription: a top-level `:` (outside any `()`/`[]` group) before the `=` of its body.
    */
  def alreadyAscribed(line: String, nameStart: Int): Boolean =
    @annotation.tailrec
    def scan(i: Int, depth: Int): Boolean =
      if i >= line.length then false
      else
        line.charAt(i) match
          case '=' if depth == 0 => false
          case ':' if depth == 0 => true
          case '(' | '['         => scan(i + 1, depth + 1)
          case ')' | ']'         => scan(i + 1, depth - 1)
          case _                 => scan(i + 1, depth)
    nameStart >= 0 && nameStart < line.length && scan(nameStart, 0)

  // --- move plan ------------------------------------------------------------

  /** A package symbol (`com/foo/bar/`) as a dotted name (`com.foo.bar`); empty for the root.
    *
    * Stainless: `@pure` — no side effects, result depends only on the input string. The standalone
    * tool can verify purity of the `stripSuffix`/`replace` pipeline.
    */
  @pure
  def packageDotted(pkgSymbol: String): String =
    pkgSymbol.stripSuffix("/").replace('/', '.')

  /** Joins a package prefix and a simple name with a `.` separator, or returns the name alone when
    * the package is empty (top-level declaration).
    *
    * Stainless contract:
    *   - Precondition: `name` must not be empty (a valid identifier).
    *   - `@pure` — deterministic, no heap effects.
    */
  @pure
  def joinFqn(pkg: String, name: String): String =
    require(name.nonEmpty)
    if pkg.isEmpty then name else s"$pkg.$name"

  /** The package a document belongs to: the owner of its first top-level global definition. `None`
    * if the document is not indexed or declares nothing top-level.
    */
  def documentPackage(uri: String): Option[String] =
    index.document(uri).flatMap { doc =>
      doc.occurrences.iterator
        .filter(_.role == s.SymbolOccurrence.Role.DEFINITION)
        .map(_.symbol)
        .filter(index.isGlobal)
        .map(index.owner)
        .find(index.isPackage)
    }

  // --- extract method plan --------------------------------------------------

  /** A local symbol's display name (locals carry no descriptor, so fall back to the index). */
  def localName(symbol: String): String =
    index.info(symbol).map(_.displayName).filter(_.nonEmpty).getOrElse(symbol)

  /** A local's rendered type, or `?` when SemanticDB recorded none — Scala leaves the inferred type
    * of an unascribed local val Empty, so the caller must supply it (the binding name is always
    * exact).
    */
  def localTypeText(symbol: String): String =
    val rendered = renderType(index.info(symbol).map(valueType).getOrElse(s.Type.Empty))
    if rendered.isEmpty then "?" else rendered

  // --- path / glob predicates -----------------------------------------------

  /** A predicate from an optional glob: `*` matches any run of chars, the rest is literal, matched
    * unanchored (substring). `None` keeps everything.
    */
  def globMatcher(pattern: Option[String]): String => Boolean =
    pattern match
      case None => _ => true
      case Some(glob) =>
        val regex = glob.split("\\*", -1).map(java.util.regex.Pattern.quote).mkString(".*").r
        uri => regex.findFirstIn(uri).isDefined

  /** A symbol-level predicate: keep a symbol when its definition uri matches the glob. A symbol
    * with no definition occurrence in the index (e.g. external types) is dropped only when a filter
    * is given. `None` keeps everything.
    */
  def bySymbolPath(pattern: Option[String]): String => Boolean =
    pattern match
      case None => _ => true
      case Some(_) =>
        val keepUri = globMatcher(pattern)
        sym => definitionUri(sym).exists(keepUri)

  /** The document uri of a symbol's definition occurrence, if the index has one. */
  def definitionUri(symbol: String): Option[String] =
    index.occurrencesOf(symbol).collectFirst {
      case (uri, occ) if occ.role == s.SymbolOccurrence.Role.DEFINITION => uri
    }

  // --- class hierarchy ------------------------------------------------------

  /** Direct parent symbols declared by a type's `ClassSignature` (empty for non-classes). */
  def directParents(info: s.SymbolInformation): List[String] =
    info.signature match
      case c: s.ClassSignature => c.parents.flatMap(parentSymbol).toList
      case _                   => Nil

  /** Depth-first transitive parents (excluding `symbol` itself), de-duplicated by first sight. */
  def linearize(symbol: String): List[String] =
    def parentsOf(sym: String): List[String] = index.info(sym).map(directParents).getOrElse(Nil)
    @annotation.tailrec
    def loop(queue: List[String], seen: Set[String], acc: List[String]): List[String] =
      queue match
        case Nil => acc.reverse
        case head :: tail =>
          if seen.contains(head) then loop(tail, seen, acc)
          else loop(parentsOf(head) ::: tail, seen + head, head :: acc)
    loop(parentsOf(symbol), Set.empty, Nil)

  /** All indexed classes/traits that declare `symbol` among their direct parents. */
  def knownSubtypes(symbol: String): List[String] =
    index.symbols.values
      .collect {
        case si if directParents(si).contains(symbol) => si.symbol
      }
      .toList
      .sorted

  // --- implicits ------------------------------------------------------------

  /** Given/implicit *definitions* (a given object or def/val) whose produced type's head is
    * `typeSymbol`. Excludes implicit parameters and the synthetic self-class a `given ... with`
    * emits (whose members are owned by an implicit type).
    */
  def implicitsProducing(typeSymbol: String): List[s.SymbolInformation] =
    index.symbols.values
      .collect {
        case si if isGivenDefinition(si) && parentSymbol(producedType(si)).contains(typeSymbol) =>
          si
      }
      .toList
      .sortBy(_.symbol)

  def isGivenDefinition(info: s.SymbolInformation): Boolean =
    val k = info.kind
    isImplicit(info) &&
    (k == s.SymbolInformation.Kind.OBJECT || k == s.SymbolInformation.Kind.METHOD) &&
    !index.info(index.owner(info.symbol)).exists(isImplicit)

  /** The type an implicit instance provides: a given object's first non-Object parent, or a given
    * def/val's result type (unwrapping the synthetic self-class a `given ... with` emits).
    */
  def producedType(info: s.SymbolInformation): s.Type =
    info.signature match
      case c: s.ClassSignature  => c.parents.find(notObject).getOrElse(s.Type.Empty)
      case m: s.MethodSignature => unwrapSelfClass(m.returnType, info.displayName)
      case v: s.ValueSignature  => unwrapSelfClass(v.tpe, info.displayName)
      case _                    => s.Type.Empty

  /** If `tpe`'s head is the synthetic class named after the given (`given x ... with`), replace it
    * with the interface that class extends; otherwise return `tpe` unchanged.
    */
  def unwrapSelfClass(tpe: s.Type, givenName: String): s.Type =
    val isSelfClass = parentSymbol(tpe).exists(sym => index.displayName(sym) == givenName)
    if !isSelfClass then tpe
    else
      parentSymbol(tpe)
        .flatMap(index.info)
        .map(_.signature)
        .collect { case c: s.ClassSignature => c.parents.find(notObject) }
        .flatten
        .getOrElse(tpe)

  def notObject(tpe: s.Type): Boolean =
    !parentSymbol(tpe).contains("java/lang/Object#")

  /** Head symbols of an implicit method's implicit-parameter types (its dependencies). */
  def implicitDependencyHeads(info: s.SymbolInformation): List[String] =
    info.signature match
      case m: s.MethodSignature =>
        m.parameterLists.toList
          .flatMap(scope => scopeInfos(Some(scope)))
          .filter(isImplicit)
          .flatMap(p => parentSymbol(valueType(p)))
      case _ => Nil

  // --- shared helpers -------------------------------------------------------

  /** Member symbols declared in a type's `ClassSignature.declarations` scope. */
  def declarationSymbols(symbol: String): List[String] =
    index
      .info(symbol)
      .map(_.signature)
      .collect { case c: s.ClassSignature => scopeInfos(c.declarations).map(_.symbol).toList }
      .getOrElse(Nil)

  def memberInfo(member: String, declaredIn: String): MemberInfo =
    MemberInfo(member, index.displayName(member), kindName(member), symbolRef(declaredIn))

  /** Whether the 0-based `(line, character)` position lies inside the half-open range `r`
    * (inclusive of start, exclusive of end). Thin `s.Range` adapter over the formally-verified
    * [[PureKernels.rangeContains]] — this method only localizes the SemanticDB `s.Range` shape; the
    * geometry (and its `[start, end)` half-open contract) is verified there.
    */
  def rangeContains(r: s.Range, line: Int, character: Int): Boolean =
    PureKernels.rangeContains(
      r.startLine,
      r.startCharacter,
      r.endLine,
      r.endCharacter,
      line,
      character
    )

  /** A range's span as a single sortable key: lines dominate (×10000), columns break ties. Used by
    * [[Analyzer.typeAtPosition]] to pick the most specific (smallest-span) occurrence covering a
    * position, so the key MUST stay non-negative. Thin `s.Range` adapter over the formally-verified
    * [[PureKernels.rangeSpan]], which proves the non-negativity invariant and computes in `Long` to
    * make the overflow that an `Int` form suffers impossible for any 32-bit position.
    */
  def rangeSpan(r: s.Range): Long =
    PureKernels.rangeSpan(r.startLine, r.endLine, r.startCharacter, r.endCharacter)

  /** A symbol's type as text: a method's return, a value's type, else the symbol's own name. */
  def typeString(symbol: String): String =
    index.info(symbol).map(_.signature) match
      case Some(m: s.MethodSignature) => renderType(m.returnType)
      case Some(v: s.ValueSignature)  => renderType(v.tpe)
      case _                          => index.displayName(symbol)

  def location(uri: String, range: Option[s.Range]): Location =
    val r = range.getOrElse(s.Range.defaultInstance)
    Location(
      uri,
      Range(Position(r.startLine, r.startCharacter), Position(r.endLine, r.endCharacter))
    )

  def symbolRef(symbol: String): SymbolRef =
    SymbolRef(symbol, index.displayName(symbol), kindName(symbol))

  def kindName(symbol: String): String =
    index.info(symbol).map(_.kind.toString).getOrElse("UNKNOWN")

  def parentSymbol(tpe: s.Type): Option[String] =
    tpe match
      case s.TypeRef(_, sym, _) => Some(sym)
      case s.SingleType(_, sym) => Some(sym)
      case _                    => None

  def scopeInfos(scope: Option[s.Scope]): Seq[s.SymbolInformation] =
    scope.toSeq.flatMap { sc =>
      if sc.hardlinks.nonEmpty then sc.hardlinks
      else sc.symlinks.flatMap(index.info)
    }

  def valueType(info: s.SymbolInformation): s.Type =
    info.signature match
      case v: s.ValueSignature  => v.tpe
      case m: s.MethodSignature => m.returnType
      case _                    => s.Type.Empty

  def isImplicit(info: s.SymbolInformation): Boolean =
    (info.properties & s.SymbolInformation.Property.IMPLICIT.value) != 0

  def renderMethod(
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
  def renderType(tpe: s.Type): String =
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
      case s.ConstantType(c)       => renderConstant(c)
      case _                       => ""

  /** Render a literal/constant type (Scala 3 singleton-literal types, e.g. `42`, `"x"`, `true`). */
  def renderConstant(c: s.Constant): String =
    c match
      case s.IntConstant(v)     => v.toString
      case s.LongConstant(v)    => s"${v}L"
      case s.FloatConstant(v)   => s"${v}f"
      case s.DoubleConstant(v)  => v.toString
      case s.BooleanConstant(v) => v.toString
      case s.CharConstant(v)    => s"'${v.toChar}'"
      case s.StringConstant(v)  => s"\"$v\""
      case s.ShortConstant(v)   => v.toString
      case s.ByteConstant(v)    => v.toString
      case s.UnitConstant()     => "Unit"
      case s.NullConstant()     => "null"
      case _                    => ""
