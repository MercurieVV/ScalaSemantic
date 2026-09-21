package com.github.mercurievv.scalasemantic.pc

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.defn
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
  private val driver = new InteractiveDriver(
    List(
      "-classpath",
      classpath.map(_.toAbsolutePath.toString).mkString(java.io.File.pathSeparator)
    ) ++ options
  )

  /** `offset` is a 0-based character offset into `code`. */
  def at(fileUri: URI, code: String, offset: Int): Result =
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
            val hole = Hole(if isUndefined(head) then "???" else "expression", head.show.take(80))
            val expected = InferCompletionType.inferType(path) match
              case Some(tpe) if !tpe.isErroneous && !prescribedByInferredTpt(path) =>
                Expected(Some(printer.tpe(tpe.widenTermRefExpr)), Confidence.Exact, whyOf(path))
              case _ =>
                Expected(
                  None,
                  Confidence.Unconstrained,
                  "no type prescribed here — declare the val's type or the def's result type first"
                )
            Result(
              found = true,
              why = None,
              hole = Some(hole),
              expected = Some(expected),
              bindings = Nil,
              producers = Nil,
              givens = Nil,
              types = Nil,
              omitted = Omitted(0, 0, 0, 0)
            )

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
    * performs so `whyOf` and the inferred type agree on the prescriber.
    */
  private def prescriber(path: List[tpd.Tree]): List[tpd.Tree] =
    path.drop(1).dropWhile(_.isInstanceOf[tpd.Block])

  /** `val x = ???` types `???` against an inferred `Nothing` — the compiler has an answer, but it
    * is the hole's own type, not a demand on it. Treat an inferred `tpt` as unconstrained.
    */
  private def prescribedByInferredTpt(path: List[tpd.Tree]): Boolean =
    prescriber(path) match
      case (d: tpd.ValOrDefDef) :: _ =>
        d.tpt match
          case tt: tpd.TypeTree => tt.isInferred
          case _                => false
      case _ => false

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
        val idx = a.args.indexWhere(_.span.contains(holeSpan))
        s"argument ${idx + 1} of ${a.fun.symbol.name.show}"
      case (_: tpd.If) :: _      => "propagated from the enclosing if"
      case (_: tpd.CaseDef) :: _ => "propagated from the enclosing match"
      case (_: tpd.Typed) :: _   => "the ascribed type"
      case _                     => "prescribed by the enclosing expression"

  def close(): Unit =
    () // InteractiveDriver holds no external resources; kept for symmetry with the backend

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
  val maxProducers = 15
  val maxGivens = 10
  val maxTypes = 5

  /** The running JVM's classpath — for dogfooding/tests, mirrors
    * `PresentationCompilerBackend.fromCurrentJvm`.
    */
  def runtimeClasspath: Seq[Path] = PresentationCompilerBackend.runtimeClasspath
