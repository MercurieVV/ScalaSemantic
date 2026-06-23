package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Stateless rendering/lookup helpers over a [[SemanticIndex]]: the symbol→text printers and the
  * small pure SemanticDB accessors that [[Analyzer]] leans on across every tool. Extracted so the
  * analyzer stays focused on query logic and delegates the "how do I say this in text / read this
  * field" concerns here. Holds no state beyond the index; safe to share.
  */
final class SemanticRendering(index: SemanticIndex):

  def rangeContains(r: s.Range, line: Int, character: Int): Boolean =
    val afterStart = line > r.startLine || (line == r.startLine && character >= r.startCharacter)
    val beforeEnd = line < r.endLine || (line == r.endLine && character < r.endCharacter)
    afterStart && beforeEnd

  def rangeSpan(r: s.Range): Int =
    (r.endLine - r.startLine) * 10000 + (r.endCharacter - r.startCharacter)

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
