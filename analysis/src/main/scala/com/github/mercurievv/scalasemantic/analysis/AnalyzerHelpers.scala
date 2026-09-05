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
    !index.isLocal(symbol) && index.info(symbol).exists { si =>
      outlineKinds.contains(si.kind) && si.displayName.nonEmpty && si.displayName != "<init>"
    }

  // --- annotated source -----------------------------------------------------

  /** An implicit-insertion or inferred-type-argument annotation for one synthetic. Each note's text
    * is self-anchored to the call it applies to (`a.map[String]`, `(using Show[A])`) rather than a
    * column, so the renderer prints it verbatim. `None` for synthetics we do not surface.
    */
  def syntheticAnnotation(
      syn: s.Synthetic,
      sourceLines: IndexedSeq[String]
  ): Option[SourceAnnotation] =
    val r = syn.range.getOrElse(s.Range.defaultInstance)
    syn.tree match
      case t: s.TypeApplyTree if t.typeArguments.nonEmpty =>
        // Anchor the inferred type-args to the CALL they apply to (`a.map[String]`), not the token
        // at the range start — which is the receiver (`a`), reading as `a[String]`.
        val targs = t.typeArguments.map(renderType).mkString("[", ", ", "]")
        val anchor = functionAnchor(t.function, sourceLines)
        Some(
          SourceAnnotation(
            r.startLine,
            r.startCharacter,
            r.endCharacter,
            "inferred-type-args",
            if anchor.isEmpty then targs else s"$anchor$targs"
          )
        )
      case app: s.ApplyTree =>
        app.function match
          // using-args appended to a visible call: the function IS the original expression.
          case _: s.OriginalTree =>
            val args = app.arguments.iterator.flatMap(insertedSymbol).map(givenDisplay).toList
            Option.when(args.nonEmpty)(
              SourceAnnotation(
                r.startLine,
                r.startCharacter,
                r.endCharacter,
                "implicit",
                args.mkString("(using ", ", ", ")")
              )
            )
          // an implicit conversion wraps the original expression: show `conv(convertedExpr)`.
          // `apply` is the compiler's `.apply` insertion, not a real conversion — the type-args
          // note already covers that call, so drop it here.
          case fn =>
            insertedName(fn).filter(_ != "apply").map { c =>
              val arg =
                app.arguments.headOption.flatMap(convertedText(_, sourceLines)).getOrElse("…")
              SourceAnnotation(
                r.startLine,
                r.startCharacter,
                r.endCharacter,
                "implicit-conversion",
                s"$c($arg)"
              )
            }
      case _ => None

  /** The symbol an inserted implicit tree (a given/implicit/conversion reference) resolves to. */
  def insertedSymbol(tree: s.Tree): Option[String] =
    tree match
      case t: s.IdTree        => Some(t.symbol)
      case t: s.SelectTree    => t.id.flatMap(insertedSymbol)
      case t: s.TypeApplyTree => insertedSymbol(t.function)
      case t: s.ApplyTree     => insertedSymbol(t.function)
      case _                  => None

  /** Plain display name of an inserted implicit tree (used for conversion names — NOT type-ified).
    */
  def insertedName(tree: s.Tree): Option[String] =
    insertedSymbol(tree).map(index.displayName)

  /** Every global symbol referenced anywhere inside a synthetic tree — function AND arguments,
    * recursively. Unlike [[insertedSymbol]] (which follows only the applied function) this reaches
    * the summoned givens carried as arguments (`ApplyTree(render, [IdTree(doubleShow)])`), the very
    * names an implicit summon inserts with no textual occurrence. Used by import-explosion to learn
    * which members of an imported prefix are actually used.
    */
  def treeSymbols(tree: s.Tree): List[String] =
    tree match
      case t: s.IdTree        => List(t.symbol)
      case t: s.SelectTree    => treeSymbols(t.qualifier) ++ t.id.toList.flatMap(treeSymbols)
      case t: s.ApplyTree     => treeSymbols(t.function) ++ t.arguments.flatMap(treeSymbols)
      case t: s.TypeApplyTree => treeSymbols(t.function)
      case t: s.FunctionTree  => t.parameters.toList.flatMap(treeSymbols) ++ treeSymbols(t.body)
      case t: s.MacroExpansionTree => treeSymbols(t.beforeExpansion)
      case _                       => Nil

  /** How to name a summoned given: its identifier when informative, else its TYPE. A synthetic
    * evidence/`x$` parameter (`evidence$1`) or a type-like capitalised standard given (`Int`, the
    * `Ordering.Int` val) tells the reader nothing — render `Show[A]` / `Ordering[Int]` instead.
    */
  private def givenDisplay(symbol: String): String =
    val name = index.displayName(symbol)
    val uninformative = name.isEmpty || name.contains('$') || name.headOption.exists(_.isUpper)
    if !uninformative then name
    else
      val tpe = renderType(index.info(symbol).map(valueType).getOrElse(s.Type.Empty))
      if tpe.nonEmpty then tpe
      else
        // stdlib givens (`Ordering.Int`) carry no indexed signature — synthesize `Owner[Name]`.
        val owner = index.displayName(index.owner(symbol))
        if owner.nonEmpty && name.nonEmpty then s"$owner[$name]" else name

  /** The call a synthetic's function applies to, for anchoring a type-args note: `render`, `a.map`,
    * `List.apply`. Reuses [[renderTree]] to render the function expression, then collapses a
    * complex receiver (one containing a nested call) to just its trailing `.method`, so the note
    * never re-inlines a whole sub-expression.
    */
  def functionAnchor(tree: s.Tree, sourceLines: IndexedSeq[String]): String =
    val full = renderTree(tree, sourceLines)
    if full.nonEmpty && !full.contains('(') && full.length <= 24 then full
    else
      tree match
        case t: s.TypeApplyTree => functionAnchor(t.function, sourceLines)
        case t: s.SelectTree    => t.id.map(id => s".${renderTree(id, sourceLines)}").getOrElse("…")
        case _ if full.isEmpty  => ""
        case _                  =>
          // complex `OriginalTree` (e.g. `List(...).sortBy`): keep only the trailing `.member`.
          val dot = full.lastIndexOf('.')
          if dot >= 0 && dot < full.length - 1 && !full.substring(dot + 1).exists("([".contains(_))
          then s".${full.substring(dot + 1)}"
          else "…"

  /** Source text of the expression an implicit conversion wrapped, if recoverable. */
  private def convertedText(tree: s.Tree, sourceLines: IndexedSeq[String]): Option[String] =
    tree match
      case o: s.OriginalTree =>
        val src = originalText(o.range, sourceLines, Map.empty)
        Option.when(src.nonEmpty)(src)
      case _ => None

  /** Render one synthetic tree as a compact elaborated expression. */
  def renderTree(
      tree: s.Tree,
      sourceLines: IndexedSeq[String],
      typeApps: Map[(Int, Int, Int), String] = Map.empty
  ): String =
    tree match
      case t: s.ApplyTree =>
        val base = renderTree(t.function, sourceLines, typeApps)
        val args = t.arguments.map(renderTree(_, sourceLines, typeApps)).filter(_.nonEmpty)
        val originalFunction = t.function match
          case _: s.OriginalTree => true
          case _                 => false
        if args.isEmpty then base
        else if originalFunction || t.arguments.forall(insertedName(_).nonEmpty) then
          // using-args: name each summoned given by identifier-or-type (so `evidence$1` -> `Show[A]`,
          // `Ordering.Int` -> `Ordering[Int]`), same rule the terse path uses.
          val usingArgs =
            t.arguments.map(renderUsingArg(_, sourceLines, typeApps)).filter(_.nonEmpty)
          s"$base${usingArgs.mkString("(using ", ", ", ")")}"
        else
          val renderedArgs = t.arguments match
            case Seq(orig: s.OriginalTree) =>
              originalText(orig.range, sourceLines, typeApps).dropWhile(_ != '(') match
                case ""   => args.mkString("(", ", ", ")")
                case call => call
            case _ => args.mkString("(", ", ", ")")
          s"$base$renderedArgs"
      case t: s.TypeApplyTree =>
        val base = renderTree(t.function, sourceLines, typeApps)
        val args = t.typeArguments.map(renderType).filter(_.nonEmpty)
        if args.isEmpty then base else args.mkString(s"$base[", ", ", "]")
      case t: s.SelectTree =>
        val base = renderTree(t.qualifier, sourceLines, typeApps)
        val name = t.id.map(renderTree(_, sourceLines, typeApps)).getOrElse("")
        if base.isEmpty then name else s"$base.$name"
      case t: s.IdTree       => index.displayName(t.symbol)
      case t: s.OriginalTree =>
        originalText(t.range, sourceLines, typeApps)
      case _ => ""

  /** Render a summoned given in using-argument position: a bare given by [[givenDisplay]] (so an
    * evidence/`Ordering.Int` name becomes its type), a nested given-application recursively (so
    * `listShow(using intShow)` keeps its shape), otherwise defer to [[renderTree]].
    */
  private def renderUsingArg(
      tree: s.Tree,
      sourceLines: IndexedSeq[String],
      typeApps: Map[(Int, Int, Int), String]
  ): String =
    tree match
      case t: s.IdTree     => givenDisplay(t.symbol)
      case t: s.SelectTree => t.id.flatMap(insertedSymbol).map(givenDisplay).getOrElse("")
      case t: s.ApplyTree  =>
        val base = renderUsingArg(t.function, sourceLines, typeApps)
        val nested = t.arguments.map(renderUsingArg(_, sourceLines, typeApps)).filter(_.nonEmpty)
        if nested.isEmpty then base else s"$base${nested.mkString("(using ", ", ", ")")}"
      case t: s.TypeApplyTree =>
        val base = renderUsingArg(t.function, sourceLines, typeApps)
        val targs = t.typeArguments.map(renderType).filter(_.nonEmpty)
        if targs.isEmpty then base else targs.mkString(s"$base[", ", ", "]")
      case other => renderTree(other, sourceLines, typeApps)

  def typeApplyOriginalRange(tree: s.Tree): Option[s.Range] =
    tree match
      case t: s.OriginalTree => t.range
      case t: s.SelectTree   => typeApplyOriginalRange(t.qualifier)
      case _                 => None

  def originalText(
      range: Option[s.Range],
      sourceLines: IndexedSeq[String],
      typeApps: Map[(Int, Int, Int), String]
  ): String =
    range match
      case Some(r) if r.startLine == r.endLine =>
        val src = sourceLines.lift(r.startLine).getOrElse("")
        val base = src.slice(r.startCharacter, r.endCharacter)
        val contained = typeApps.toList
          .collect {
            case ((line, start, end), args)
                if line == r.startLine && start >= r.startCharacter && end <= r.endCharacter =>
              (start - r.startCharacter, end - r.startCharacter, args)
          }
          // Each splice happens AT `end`, so the fold must run in descending END order. Sorting by
          // start is a different ordering: a nested type application (`List.apply[String]`) starts
          // after the range that encloses it (`sizes(…).sum[Int]`) but ends well before it, so
          // start-order splices the inner one first and shifts the outer one's index off its mark —
          // landing the type args inside an argument, e.g. `"bb[Int]"`.
          .sortBy { case (start, end, _) => (-end, -start) }
        contained.foldLeft(base) { case (acc, (_, end, args)) =>
          acc.take(end) + args + acc.drop(end)
        }
      case _ => ""

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
            Some(
              SourceAnnotation(
                r.startLine,
                r.startCharacter,
                r.endCharacter,
                "inferred-type",
                s": $rendered"
              )
            )
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

  /** Dotted FQN of a type symbol: `scala/collection/immutable/List#` ->
    * `scala.collection.immutable.List`.
    */
  @pure
  def typeSymbolFqn(sym: String): String =
    if sym == "scala/package.List#" then "scala.collection.immutable.List"
    else sym.stripSuffix("#").replace('/', '.').replace('#', '.')

  /** Blank out line and block comments while preserving line count and string literals. */
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.MutableDataStructures",
      "org.wartremover.warts.Var",
      "org.wartremover.warts.While"
    )
  )
  def stripComments(lines: IndexedSeq[String]): IndexedSeq[String] =
    val text = lines.mkString("\n")
    val out = new StringBuilder(text.length)
    // scalafix:off DisableSyntax.var
    var i = 0
    var inString = false
    var inChar = false
    var block = 0
    while i < text.length do
      val c = text.charAt(i)
      val d = if i + 1 < text.length then text.charAt(i + 1) else '\u0000'
      if block > 0 then
        if c == '*' && d == '/' then
          out.append("  ")
          i += 2
          block -= 1
        else
          out.append(if c == '\n' then '\n' else ' ')
          i += 1
      else if inString then
        out.append(c)
        if c == '\\' then
          out.append(d)
          i += 2
        else
          if c == '"' then inString = false
          i += 1
      else if inChar then
        out.append(c)
        if c == '\\' then
          out.append(d)
          i += 2
        else
          if c == '\'' then inChar = false
          i += 1
      else if c == '"' then
        inString = true
        out.append(c)
        i += 1
      else if c == '\'' then
        inChar = true
        out.append(c)
        i += 1
      else if c == '/' && d == '/' then
        while i < text.length && text.charAt(i) != '\n' do
          out.append(' ')
          i += 1
      else if c == '/' && d == '*' then
        block += 1
        out.append("  ")
        i += 2
      else
        out.append(c)
        i += 1
    // scalafix:on DisableSyntax.var
    out.toString.split("\n", -1).toIndexedSeq

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
      case None       => _ => true
      case Some(glob) =>
        val regex = glob.split("\\*", -1).map(java.util.regex.Pattern.quote).mkString(".*").r
        uri => regex.findFirstIn(uri).isDefined

  /** A symbol-level predicate: keep a symbol when its definition uri matches the glob. A symbol
    * with no definition occurrence in the index (e.g. external types) is dropped only when a filter
    * is given. `None` keeps everything.
    */
  def bySymbolPath(pattern: Option[String]): String => Boolean =
    pattern match
      case None    => _ => true
      case Some(_) =>
        val keepUri = globMatcher(pattern)
        sym => definitionUri(sym).exists(keepUri)

  /** The document uri of a symbol's definition occurrence, if the index has one. */
  def definitionUri(symbol: String): Option[String] =
    index
      .occurrencesOf(symbol)
      .collectFirst:
        case (uri, occ) if occ.role == s.SymbolOccurrence.Role.DEFINITION => uri

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
        case Nil          => acc.reverse
        case head :: tail =>
          if seen.contains(head) then loop(tail, seen, acc)
          else loop(parentsOf(head) ::: tail, seen + head, head :: acc)
    loop(parentsOf(symbol), Set.empty, Nil)

  /** All indexed classes/traits that declare `symbol` among their direct parents. */
  def knownSubtypes(symbol: String): List[String] =
    index.symbols.values
      .collect:
        case si if directParents(si).contains(symbol) => si.symbol
      .toList
      .sorted

  // --- implicits ------------------------------------------------------------

  /** Given/implicit *definitions* (a given object or def/val) whose produced type's head is
    * `typeSymbol`. Excludes implicit parameters and the synthetic self-class a `given ... with`
    * emits (whose members are owned by an implicit type).
    */
  def implicitsProducing(typeSymbol: String): List[s.SymbolInformation] =
    index.symbols.values
      .collect:
        case si if isGivenDefinition(si) && parentSymbol(producedType(si)).contains(typeSymbol) =>
          si
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

  def implicitDependencyTypes(info: s.SymbolInformation): List[s.Type] =
    info.signature match
      case m: s.MethodSignature =>
        m.parameterLists.toList
          .flatMap(scope => scopeInfos(Some(scope)))
          .filter(isImplicit)
          .map(valueType)
      case _ => Nil

  def typeParameterNames(info: s.SymbolInformation): Set[String] =
    info.signature match
      case m: s.MethodSignature => scopeInfos(m.typeParameters).map(_.displayName).toSet
      case _                    => Set.empty

  // --- shared helpers -------------------------------------------------------

  /** Member symbols declared in a type's `ClassSignature.declarations` scope. */
  def declarationSymbols(symbol: String): List[String] =
    index
      .info(symbol)
      .flatMap(_.signature match
        case c: s.ClassSignature => Some(scopeInfos(c.declarations).map(_.symbol).toList)
        case _                   => None)
      .getOrElse(Nil)

  // --- product records (case classes) -----------------------------------------

  /** The class half of a type/companion pair: `p/Foo#` for both `p/Foo#` and `p/Foo.`. `None` when
    * the derived symbol is unknown to the index, so we never fabricate a symbol that this
    * compiler/version did not emit.
    */
  def classSymbolOf(symbol: String): Option[String] =
    if symbol.endsWith("#") then Some(symbol).filter(known)
    else if symbol.endsWith(".") then Some(symbol.dropRight(1) + "#").filter(known)
    else None

  /** The companion-object term symbol `p/Foo.` for a class symbol `p/Foo#`, when the index knows
    * it.
    *
    * This is the one that matters: in the SemanticDB emitted by Scala 3, a `Foo(...)` construction
    * site resolves to the companion **object** symbol, not to `Foo.apply().` and not to
    * ``Foo#`<init>`().`` — both of which are frequently absent from the index entirely.
    */
  def companionObjectOf(classSymbol: String): Option[String] =
    Option.when(classSymbol.endsWith("#"))(classSymbol.dropRight(1) + ".").filter(known)

  /** True for a case class / case object / enum case — anything carrying SemanticDB's `CASE`
    * property, i.e. the types whose construction and `copy` sites live on generated members.
    */
  def isCaseLike(symbol: String): Boolean =
    classSymbolOf(symbol).exists { cls =>
      index.info(cls).exists(si => (si.properties & s.SymbolInformation.Property.CASE.value) != 0)
    }

  /** Symbols generated for a product record, each labelled by how it relates to the class, in a
    * stable order. Every symbol is index-verified: nothing here is string-built and returned
    * unchecked, because Scala 2.13 and Scala 3 disagree about which of these members exist.
    */
  def relatedProductSymbols(symbol: String): List[(String, String)] =
    classSymbolOf(symbol).filter(_ => isCaseLike(symbol)).toList.flatMap { cls =>
      val decls = declarationSymbols(cls)
      val companion = companionObjectOf(cls)
      val constructors = decls.filter(named("<init>"))
      val paramNames = constructorParamNames(constructors)
      val related =
        companion.map("companion" -> _).toList
          ++ constructors.map("constructors" -> _)
          ++ decls.filter(named("copy")).map("copy" -> _)
          ++ decls.filter(s => paramNames.contains(index.displayName(s))).map("accessors" -> _)
          ++ companion.toList.flatMap(declarationSymbols).collect {
            case s if named("apply")(s)   => "apply" -> s
            case s if named("unapply")(s) => "unapply" -> s
          }
      related.filterNot(_._2 == symbol).distinct
    }

  /** Display names of the primary constructor's first parameter list — the case-class fields whose
    * accessors we surface. Empty when no constructor is indexed.
    */
  private def constructorParamNames(constructors: List[String]): Set[String] =
    constructors
      .flatMap(index.info)
      .collect { case si =>
        si.signature match
          case m: s.MethodSignature =>
            m.parameterLists.headOption.toList.flatMap(sc =>
              scopeInfos(Some(sc)).map(_.displayName)
            )
          case _ => Nil
      }
      .flatten
      .toSet

  private def named(name: String)(symbol: String): Boolean = index.displayName(symbol) == name

  private def known(symbol: String): Boolean =
    index.info(symbol).isDefined || index.occurrencesOf(symbol).nonEmpty

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

  def kindName(symbol: String): SymbolKind =
    SymbolKind.from(index.info(symbol).map(_.kind.toString).getOrElse("UNKNOWN"))

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

  /** Compact signature-suffix rendering of a type-alias / opaque-type / abstract-type declaration.
    *
    * A transparent alias has `lowerBound == upperBound == RHS` and renders as `[T] = <RHS>`; a
    * bounded or opaque type renders as `[T] >: <Lo> <: <Hi>`, dropping a trivial `Nothing` lower or
    * `Any` upper bound. SemanticDB hides an opaque type's RHS, so those surface as `>: Nothing <:
    * Any` rather than empty. One level only — no transitive dealiasing.
    */
  def renderTypeSignature(t: s.TypeSignature): String =
    val tps = scopeInfos(t.typeParameters).map(_.displayName)
    val tp = if tps.isEmpty then "" else tps.mkString("[", ", ", "]")
    val lo = renderType(t.lowerBound)
    val hi = renderType(t.upperBound)
    val bounds =
      if t.lowerBound == t.upperBound && lo.nonEmpty then s"= $lo"
      else
        val loPart = if lo.isEmpty || lo == "Nothing" then "" else s">: $lo"
        val hiPart = if hi.isEmpty || hi == "Any" then "" else s"<: $hi"
        List(loPart, hiPart).filter(_.nonEmpty).mkString(" ") match
          case ""    => ">: Nothing <: Any"
          case other => other
    s"$tp $bounds".strip

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
