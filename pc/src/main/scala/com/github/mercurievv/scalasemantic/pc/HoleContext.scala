package com.github.mercurievv.scalasemantic.pc

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameOps.stripModuleClassSuffix
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Symbols.defn
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.Types.TypeParamRef
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.pc.IndexedContext
import dotty.tools.pc.InferCompletionType
import dotty.tools.pc.printer.ShortenedTypePrinter
import dotty.tools.pc.printer.ShortenedTypePrinter.IncludeDefaultParam
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.pc.reports.EmptyReportContext
import scala.meta.pc.reports.ReportContext

import java.net.URI
import java.nio.file.Path

/** The typed-hole query (spec 2026-09-21-typed-hole-context-design §2): what type does a position
  * demand, and what in scope satisfies it. Runs its own dotty [[InteractiveDriver]] on the same
  * classpath as the module's presentation compiler — error-tolerant, so a buffer that does not yet
  * compile still answers. The expected type comes from the PC's own `InferCompletionType`, the
  * in-scope pool from its `IndexedContext`; every candidate is checked with the compiler's `<:<`,
  * never by comparing rendered strings. Not thread-safe; one instance per module backend.
  */
final class HoleContext(classpath: Seq[Path], options: List[String]) extends AutoCloseable:
  import HoleContext.*

  private given ReportContext = new EmptyReportContext()
  // `-color:never`: dotty defaults to `always`, and the rendered types/signatures below go into JSON
  // string fields, where an ANSI escape is noise the agent has to strip (and the docs example lies).
  private val driver = new InteractiveDriver(
    List(
      "-classpath",
      classpath.map(_.toAbsolutePath.toString).mkString(java.io.File.pathSeparator),
      "-color:never"
    ) ++ options
  )

  /** `offset` is a 0-based character offset into `code`. */
  def at(fileUri: URI, code: String, offset: Int): Result =
    if offset < 0 || offset > code.length then notFound("position is outside the file")
    else
      val source = SourceFile.virtual(fileUri, code)
      driver.run(fileUri, source)
      val ctx0 = driver.currentCtx
      driver.compilationUnits.get(fileUri) match
        case None       => notFound("the compiler produced no unit for this file")
        case Some(unit) =>
          given Context = ctx0.fresh.setCompilationUnit(unit)
          val pos = SourcePosition(source, Span(offset))
          val path = Interactive.pathTo(unit.tpdTree, pos.span)
          path.headOption match
            case None                              => notFound("no tree at this position")
            case Some(head) if !isExpression(head) =>
              notFound(s"position is not inside an expression (${head.getClass.getSimpleName})")
            case Some(head) =>
              val located = Interactive.contextOfPath(path)
              val indexed = IndexedContext(pos)(using located)
              val printer =
                ShortenedTypePrinter(EmptySymbolSearch, IncludeDefaultParam.Never)(using indexed)
              val hole =
                Hole(if isUndefined(head) then "???" else "expression", holeText(head, code))
              val expectedTpe = InferCompletionType.inferType(path) match
                case Some(tpe)
                    if !tpe.isErroneous && !prescribedByInferredTpt(path) &&
                      !inStatementPosition(path) =>
                  Some(tpe.widenTermRefExpr)
                case _ => None
              val expected = expectedTpe match
                case Some(tpe) => Expected(Some(printer.tpe(tpe)), Confidence.Exact, whyOf(path))
                case None      =>
                  Expected(
                    None,
                    Confidence.Unconstrained,
                    "no type prescribed here — declare the val's type or the def's result type first"
                  )
              candidates(hole, expected, expectedTpe, indexed, printer)(using located)

  /** The hole as written: the source slice under the tree's span, not the typed tree's `show`
    * (which qualifies names).
    */
  private def holeText(t: tpd.Tree, code: String)(using Context): String =
    val span = t.span
    val slice =
      if span.exists && span.start >= 0 && span.end <= code.length && span.start <= span.end then
        code.substring(span.start, span.end).nn
      else t.show
    slice.take(80)

  /** Fill the candidate lists for an `expected` type: every check is the compiler's `<:<` on the
    * dealiased expected type; nothing compares rendered strings.
    */
  private def candidates(
      hole: Hole,
      expected: Expected,
      expectedTpe: Option[Type],
      indexed: IndexedContext,
      printer: ShortenedTypePrinter
  )(using Context): Result =
    expectedTpe match
      case None =>
        Result(true, None, Some(hole), Some(expected), Nil, Nil, Nil, Nil, Omitted(0, 0, 0, 0))
      case Some(want0) =>
        val want = want0.dealias
        val pool: List[Symbol] = indexed.scopeSymbols.distinct
        val wantClasses =
          (want.classSymbol :: want.argInfos.map(_.classSymbol)).filter(_.exists).distinct

        // --- bindings: values (and parameterless defs) whose own type conforms -------------------
        def resultOf(s: Symbol) = s.info.widen.finalResultType
        def isValueLike(s: Symbol) =
          s.isTerm && !s.is(Flags.Package) && !s.is(Flags.Module) && !s.isConstructor &&
            (!s.is(Flags.Method) || s.info.widen.paramInfoss.isEmpty)
        def conforms(res: Type) = !res.isErroneous && !res.isNothingType && res <:< want
        def kindOf(s: Symbol) =
          if s.is(Flags.Param) then "param" else if s.is(Flags.Method) then "def" else "val"
        def ownerName(s: Symbol) = s.owner.name.stripModuleClassSuffix.show
        def fromOf(s: Symbol) =
          if s.is(Flags.Param) || s.owner.is(Flags.Method) then s"enclosing def ${ownerName(s)}"
          else if s.owner.is(Flags.Module) || s.owner.isClass then s"member of ${ownerName(s)}"
          else "in scope"

        // --- how a candidate can be WRITTEN here ---------------------------------------------
        // Never the raw `symbol.name`: a name is only offered when this same `IndexedContext`
        // says it resolves back to that symbol at this position — a renaming import's alias when
        // there is one, else the short name only when every symbol that name finds here is this
        // one (so a shadowed binding is not offered under a name that means something else).
        // A statically reachable symbol that fails both is emitted fully qualified
        // (`cats.effect.IO.unit` for an un-imported `IO`); anything else is dropped.
        def relatedTo(s: Symbol)(o: Symbol) =
          o == s || o == s.companionModule || o == s.companionClass
        def shortNameOf(s: Symbol): Option[String] =
          indexed
            .rename(s)
            .orElse(indexed.findSymbol(s.name).collect {
              case syms if syms.distinct.forall(relatedTo(s)) => s.name.show
            })
        def isStatic(s: Symbol): Boolean =
          val o = s.owner
          o.exists && (o.is(Flags.Package) || (o.is(Flags.Module) && isStatic(o)))
        def fullNameOf(s: Symbol) = s.fullName.stripModuleClassSuffix.show
        def writableName(s: Symbol): Option[String] =
          shortNameOf(s).orElse(Option.when(isStatic(s))(fullNameOf(s)))

        val allBindings = pool
          .filter(isValueLike)
          .filter(s => conforms(resultOf(s)))
          .flatMap(s =>
            shortNameOf(s).map(n => Binding(n, printer.tpe(resultOf(s)), kindOf(s), fromOf(s)))
          )
          .distinctBy(_.name)

        // --- producers: methods whose result fits (exact) or is the same class (shape) ----------
        // Accessibility is the compiler's own answer from THIS position (`m.termRef` is the
        // prefix the call would be written with), so a package-private or private companion
        // member — `String.newStringUTF8NoRepl` and friends — is never offered. `Artifact` (not
        // `Synthetic`) is the flag that marks compiler bookkeeping: a case class's `apply` is
        // Synthetic and is exactly the producer the agent wants.
        val companions: List[(Symbol, String, Option[Symbol])] =
          wantClasses
            .map(_.companionModule)
            .filter(_.exists)
            .flatMap(m =>
              m.info.decls.toList
                .filter(d =>
                  d.isTerm && d.is(Flags.Method) && !d.isConstructor && !d.is(Flags.Artifact) &&
                    !d.name.show.contains("$default$") && d.isAccessibleFrom(m.termRef)
                )
                .map(d => (d, s"companion ${m.name.show}", Some(m)))
            )
        val methods: List[(Symbol, String, Option[Symbol])] =
          pool
            .filter(s => s.isTerm && s.is(Flags.Method) && s.info.widen.paramInfoss.nonEmpty)
            .map(s => (s, fromOf(s), None))
        def matchKind(s: Symbol): Option[String] =
          val res = resultOf(s)
          if res.isErroneous || res.isNothingType then None
          else if res <:< want then Some("exact")
          else if res.classSymbol.exists && want.classSymbol.exists &&
            res.classSymbol.derivesFrom(want.classSymbol)
          then Some("shape")
          else None
        def producerName(s: Symbol, qualifier: Option[Symbol]): Option[String] = qualifier match
          case Some(m) => writableName(m).map(q => s"$q.${s.name.show}")
          case None    => writableName(s)
        // A shape match whose result still carries the method's own type parameters (`pure[A]: IO[A]`)
        // can be instantiated to the expected type; one whose arguments are already fixed
        // (`monotonic: IO[FiniteDuration]`) never can, so it ranks last.
        def instantiable(s: Symbol): Boolean =
          resultOf(s).argInfos.exists(_.isInstanceOf[TypeParamRef])
        val ranked = (companions ++ methods)
          .distinctBy(_._1)
          .flatMap((s, from, qualifier) =>
            for
              k <- matchKind(s)
              n <- producerName(s, qualifier)
            yield (k, s, Producer(n, printer.defaultMethodSignature(s, s.info.widen), from, k))
          )
        val exactProducers = ranked.collect { case ("exact", _, p) => p }
        val shapeProducers = ranked
          .collect { case ("shape", s, p) => (if instantiable(s) then 0 else 1, p) }
          .sortBy(_._1)
          .map(_._2)
        // The budget is per match kind, so a companion with dozens of exact members (cats-effect's
        // `IO` has 17) can never crowd every shape match out of the answer.
        val shownProducers = exactProducers.take(maxProducers) ++ shapeProducers.take(maxProducers)
        val omittedProducers = exactProducers.size + shapeProducers.size - shownProducers.size

        // --- givens: what the compiler would pick for the expected type -------------------------
        // Lexical scope (`ctx.implicits`) plus the type's own implicit scope (companions).
        val allGivens =
          if want.classSymbol.exists && want.classSymbol.isClass && want.argInfos.nonEmpty then
            val ctx = summon[Context]
            val lexical = ctx.implicits.eligible(want)
            // `ctx.run` can be `null` outside a full compilation run; degrade to lexical-scope
            // givens only rather than NPE, so the error-tolerance guarantee still holds.
            val ofType = Option(ctx.run).map(_.implicitScope(want).eligible).getOrElse(Nil)
            (lexical ++ ofType)
              .filter(!_.isConversion)
              .map(_.ref)
              .distinctBy(_.symbol)
              .flatMap(ref =>
                writableName(ref.symbol).map(n =>
                  Given(n, printer.tpe(ref.underlying.widen.finalResultType), fromOf(ref.symbol))
                )
              )
          else Nil

        // --- types: the expected type's classes, in SemanticDB symbol form ----------------------
        // A method-local (or otherwise term-owned) class has no stable SemanticDB symbol we can
        // spell here (SemanticDB numbers `local0`, `local1`, ... per-file; we have no such counter),
        // so it is left out of `types` rather than rendered as a bogus `...foo#`.
        val allTypes = wantClasses.flatMap(cls =>
          semanticdbSymbol(cls).map(sym =>
            TypeRef(sym, cls.name.show, printer.hoverSymbol(cls, cls.info))
          )
        )

        Result(
          found = true,
          why = None,
          hole = Some(hole),
          expected = Some(expected),
          bindings = allBindings.take(maxBindings),
          producers = shownProducers,
          givens = allGivens.take(maxGivens),
          types = allTypes.take(maxTypes),
          omitted = Omitted(
            (allBindings.size - maxBindings).max(0),
            omittedProducers,
            (allGivens.size - maxGivens).max(0),
            (allTypes.size - maxTypes).max(0)
          )
        )

  /** SemanticDB symbol of a class: `a/b/C#`, nested `a/b/C#D#`, in an object `a/b/O.D#`. A
    * descriptor that is not a plain identifier (an operator name such as `::`) is backtick-escaped.
    * `None` when the class is owned by a method or other term — SemanticDB spells those `local0`,
    * `local1`, ... per-file, a counter this query has no access to, so it is better to omit the
    * type than to emit a symbol that does not resolve to anything.
    */
  private def semanticdbSymbol(cls: Symbol)(using Context): Option[String] =
    def isPlainIdentifier(name: String): Boolean =
      name.nonEmpty && (name.head.isLetter || name.head == '_') &&
        name.forall(c => c.isLetterOrDigit || c == '_' || c == '$')
    def descriptor(name: String): String =
      if isPlainIdentifier(name) then name else s"`$name`"
    def owners(s: Symbol): Option[String] =
      if !s.exists || s.isRoot || s.isEffectiveRoot || s == defn.EmptyPackageClass then Some("")
      else if s.is(Flags.Method) then None
      else if s.is(Flags.Package) then owners(s.owner).map(_ + s.name.show + "/")
      else if s.is(Flags.Module) then
        owners(s.owner).map(_ + s.name.stripModuleClassSuffix.show + ".")
      else owners(s.owner).map(_ + descriptor(s.name.show) + "#")
    owners(cls)

  private def notFound(why: String): Result =
    Result(false, Some(why), None, None, Nil, Nil, Nil, Nil, Omitted(0, 0, 0, 0))

  /** `???` is `Predef.???`, typed as `Nothing`; a `Select`/`Ident` of `defn.Predef_undefined`. */
  private def isUndefined(t: tpd.Tree)(using Context): Boolean = t match
    case tpd.Ident(_) | tpd.Select(_, _) => t.symbol == defn.Predef_undefined
    case tpd.Apply(fun, _)               => isUndefined(fun)
    case _                               => false

  private def isExpression(t: tpd.Tree): Boolean = t match
    case _: tpd.TypeTree | _: tpd.Bind | _: tpd.Import | _: tpd.PackageDef | _: tpd.Template =>
      false
    case _: tpd.DefTree => false
    case _              => t.isTerm

  /** The construct whose type the hole must satisfy: the first enclosing tree that is not a
    * transparent wrapper (`Block`, `Typed`-less parens). Mirrors the walk `InferCompletionType`
    * performs so `whyOf` and the inferred type agree on the prescriber. A `Block` is transparent
    * only for its RESULT expression — `def f: String = { ???; "x" }` puts the hole in a statement
    * position, which the def's result type does not prescribe (spec §2), so the walk stops.
    */
  private def prescriber(path: List[tpd.Tree]): List[tpd.Tree] =
    def walk(below: tpd.Tree, rest: List[tpd.Tree]): List[tpd.Tree] = rest match
      case (b: tpd.Block) :: more if b.expr eq below => walk(b, more)
      case (_: tpd.Block) :: _                       => Nil
      case _                                         => rest
    path match
      case below :: rest => walk(below, rest)
      case Nil           => Nil

  /** True when the hole is a statement of a block rather than the block's result: nothing
    * prescribes a type there, so the answer is `unconstrained`.
    */
  private def inStatementPosition(path: List[tpd.Tree]): Boolean =
    path.nonEmpty && path.tail.exists(_.isInstanceOf[tpd.Block]) && prescriber(path).isEmpty

  /** `val x = ???` types `???` against an inferred `Nothing` — the compiler has an answer, but it
    * is the hole's own type, not a demand on it. Treat an inferred `tpt` as unconstrained. Walks
    * the same transparent wrappers `InferCompletionType.inferType` descends through (`Block`, `If`
    * branches, `CaseDef` of a `Match`/`Try`, `Try`, `NamedArg`) so `def f = if c then ??? else 1`
    * is unconstrained too.
    */
  private def prescribedByInferredTpt(path: List[tpd.Tree]): Boolean =
    val holeSpan = path.headOption.map(_.span).getOrElse(Span(0))
    def walk(rest: List[tpd.Tree]): Boolean = rest match
      case (b: tpd.Block) :: more if b.expr.span.contains(holeSpan)      => walk(more)
      case tpd.If(cond, _, _) :: more if !cond.span.contains(holeSpan)   => walk(more)
      case (_: tpd.CaseDef) :: (_: tpd.Match) :: more                    => walk(more)
      case (_: tpd.CaseDef) :: (_: tpd.Try) :: more                      => walk(more)
      case tpd.Try(block, _, _) :: more if block.span.contains(holeSpan) => walk(more)
      case (_: tpd.NamedArg) :: more                                     => walk(more)
      case (d: tpd.ValOrDefDef) :: _                                     =>
        d.tpt match
          case tt: tpd.TypeTree => tt.isInferred
          case _                => false
      case _ => false
    walk(path.drop(1))

  /** Which enclosing construct prescribed the type: mirrors the cases
    * `InferCompletionType.inferType` matches.
    */
  private def whyOf(path: List[tpd.Tree])(using Context): String =
    val holeSpan = path.headOption.map(_.span).getOrElse(Span(0))
    prescriber(path) match
      case (d: tpd.ValOrDefDef) :: _ if !d.tpt.tpe.isErroneous =>
        s"result type of ${if d.symbol.is(Flags.Method) then "def" else "val"} ${d.name.show}"
      case tpd.NamedArg(name, _) :: (a: tpd.Apply) :: _ =>
        s"argument $name of ${a.fun.symbol.name.show}"
      case (a: tpd.Apply) :: _ =>
        // -1: the hole is not inside any written argument (a desugared or implicit argument list),
        // so there is no argument number to name.
        val idx = a.args.indexWhere(_.span.contains(holeSpan))
        val which = if idx < 0 then "an argument" else s"argument ${idx + 1}"
        s"$which of ${a.fun.symbol.name.show}"
      case (_: tpd.If) :: _      => "propagated from the enclosing if"
      case (_: tpd.CaseDef) :: _ => "propagated from the enclosing match"
      case (_: tpd.Typed) :: _   => "the ascribed type"
      case _                     => "prescribed by the enclosing expression"

  /** Releases what the driver exposes: the opened files and their typed trees. The driver's
    * `ClassPath` — and the jar handles its zip entries hold — is NOT released: `InteractiveDriver`
    * offers no API for that, so a module backend's hole driver keeps its classpath open for as long
    * as the backend lives.
    */
  def close(): Unit =
    driver.compilationUnits.keys.toList.foreach(driver.close)

object HoleContext:
  enum Confidence:
    case Exact, Unconstrained
  final case class Expected(tpe: Option[String], confidence: Confidence, why: String)
  final case class Hole(kind: String /* "???" | "expression" */, text: String)
  final case class Binding(
      name: String,
      tpe: String,
      kind: String /* param|val|def */,
      from: String
  )
  final case class Producer(
      name: String,
      signature: String,
      from: String,
      matchKind: String /* exact|shape */
  )
  final case class Given(name: String, tpe: String, from: String)
  final case class TypeRef(
      symbol: String /* SemanticDB symbol */,
      displayName: String,
      signature: String
  )
  final case class Omitted(bindings: Int, producers: Int, givens: Int, types: Int)
  final case class Result(
      found: Boolean,
      why: Option[String],
      hole: Option[Hole],
      expected: Option[Expected],
      bindings: List[Binding],
      producers: List[Producer],
      givens: List[Given],
      types: List[TypeRef],
      omitted: Omitted
  )
  val maxBindings = 20

  /** Per match kind: up to this many exact, plus up to this many shape matches. */
  val maxProducers = 15
  val maxGivens = 10
  val maxTypes = 5

  /** The running JVM's classpath — for dogfooding/tests, mirrors
    * `PresentationCompilerBackend.fromCurrentJvm`.
    */
  def runtimeClasspath: Seq[Path] = PresentationCompilerBackend.runtimeClasspath
