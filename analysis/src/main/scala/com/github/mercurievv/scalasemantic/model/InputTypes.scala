package com.github.mercurievv.scalasemantic.model

import eu.timepit.refined.api.RefType
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import stainless.annotation.pure

import scala.meta.internal.semanticdb.Scala.*

/** Validated inputs accepted by the analysis engine.
  *
  * These are intentionally not used in the JSON result models: MCP wire payloads stay simple, while
  * raw user input is narrowed once at the boundary before domain logic consumes it.
  */
object InputTypes:

  private type NonEmptyString = String Refined NonEmpty

  private def nonEmpty(value: String, name: String): Either[String, NonEmptyString] =
    refineV[NonEmpty](value.trim).left.map(_ => s"$name must be non-empty")

  private def nonEmptyValue(value: NonEmptyString): String = value.value

  opaque type SemanticDbSymbol = NonEmptyString
  object SemanticDbSymbol:
    def from(value: String): Either[String, SemanticDbSymbol] =
      nonEmpty(value, "SemanticDB symbol").flatMap { s =>
        val symbol = s.value
        if symbol.isGlobal || symbol.isLocal then Right(s)
        else Left(s"invalid SemanticDB symbol: $value")
      }

    extension (symbol: SemanticDbSymbol) def value: String = nonEmptyValue(symbol)

  opaque type MethodSymbol = SemanticDbSymbol
  object MethodSymbol:
    def from(value: String): Either[String, MethodSymbol] =
      SemanticDbSymbol.from(value).flatMap { symbol =>
        val raw = symbol.value
        if raw.isGlobal && raw.desc.isMethod then Right(symbol)
        else Left(s"expected method symbol: $value")
      }

    extension (symbol: MethodSymbol) def value: String = nonEmptyValue(symbol)

  opaque type TypeSymbol = SemanticDbSymbol
  object TypeSymbol:
    def from(value: String): Either[String, TypeSymbol] =
      SemanticDbSymbol.from(value).flatMap { symbol =>
        val raw = symbol.value
        if raw.isGlobal && raw.desc.isType then Right(symbol)
        else Left(s"expected type symbol: $value")
      }

    extension (symbol: TypeSymbol) def value: String = nonEmptyValue(symbol)

  opaque type PackageSymbol = String
  object PackageSymbol:
    // A SemanticDB package symbol is a run of `name/` descriptors and nothing else: slash-separated,
    // non-empty segments with no type (`#`), term (`.`), method (`()`), multi (`;`) or type-arg
    // (`[]`) descriptor characters and no whitespace. The root package is the empty string.
    // NB: scalameta's `isGlobal`/`isPackage` cannot be used to validate this — once a trailing `/`
    // is appended they accept any non-empty string — so the structure is checked explicitly here.
    private val packagePattern = """([^\s/.#;()\[\],]+/)+""".r

    def from(value: String): Either[String, PackageSymbol] =
      value.trim match
        case "" => Right("")
        case s  =>
          val normalized = if s.endsWith("/") then s else s"$s/"
          if packagePattern.matches(normalized) then Right(normalized)
          else Left(s"invalid package symbol: $value")

    extension (symbol: PackageSymbol) def value: String = symbol

  opaque type DocumentUri = NonEmptyString
  object DocumentUri:
    def from(value: String): Either[String, DocumentUri] =
      nonEmpty(value, "document uri").flatMap { uri =>
        val path = uri.value
        if path.startsWith("/") then Left(s"document uri must be relative: $value")
        else if path.split('/').contains("..") then
          Left(s"document uri must not contain '..': $value")
        else Right(uri)
      }

    extension (uri: DocumentUri) def value: String = nonEmptyValue(uri)

  type NonNegativeInt = Int Refined NonNegative
  object NonNegativeInt:
    def from(value: Int, name: String): Either[String, NonNegativeInt] =
      refineV[NonNegative](value).left.map(_ => s"$name must be >= 0: $value")

  type PositiveInt = Int Refined Positive
  object PositiveInt:
    val DefaultLimit: PositiveInt = RefType.applyRef[PositiveInt](50) match
      case Right(value) => value
      case Left(error)  => sys.error(error)

    def from(value: Int, name: String): Either[String, PositiveInt] =
      refineV[Positive](value).left.map(_ => s"$name must be > 0: $value")

  final case class SourcePosition(line: NonNegativeInt, character: NonNegativeInt):
    @pure
    def lineValue: Int = line.value
    @pure
    def characterValue: Int = character.value

    @pure
    def before(other: SourcePosition): Boolean =
      (lineValue < other.lineValue ||
        (lineValue == other.lineValue && characterValue < other.characterValue))
        .ensuring(res =>
          (if this == other then !res else true) &&
            (if res then !other.before(this) else true)
        )

    @pure
    def atOrBefore(line: Int, character: Int): Boolean =
      require(line >= 0 && character >= 0)
      lineValue < line ||
      (lineValue == line && characterValue <= character)

    @pure
    def atOrAfter(line: Int, character: Int): Boolean =
      require(line >= 0 && character >= 0)
      lineValue > line ||
      (lineValue == line && characterValue >= character)

  object SourcePosition:
    def from(line: Int, character: Int): Either[String, SourcePosition] =
      for
        l <- NonNegativeInt.from(line, "line")
        c <- NonNegativeInt.from(character, "character")
      yield SourcePosition(l, c)

  final case class SourceRange(start: SourcePosition, end: SourcePosition):
    @pure
    def startLine: Int = start.lineValue
    @pure
    def startCharacter: Int = start.characterValue
    @pure
    def endLine: Int = end.lineValue
    @pure
    def endCharacter: Int = end.characterValue

    @pure
    def contains(
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int
    ): Boolean =
      require(
        startLine >= 0 && startCharacter >= 0 &&
          endLine >= startLine &&
          (endLine > startLine || endCharacter >= startCharacter)
      )
      start.atOrBefore(startLine, startCharacter) &&
      end.atOrAfter(endLine, endCharacter)

    @pure
    def startsAtOrAfterEnd(line: Int, character: Int): Boolean =
      require(line >= 0 && character >= 0)
      end.atOrBefore(line, character)

  object SourceRange:
    def from(
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int
    ): Either[String, SourceRange] =
      for
        start <- SourcePosition.from(startLine, startCharacter)
        end <- SourcePosition.from(endLine, endCharacter)
        range <-
          if start.before(end) then Right(SourceRange(start, end))
          else Left("range end must be after range start")
      yield range

  opaque type ScalaIdentifier = NonEmptyString
  object ScalaIdentifier:
    private val plain = """[A-Za-z_$][A-Za-z0-9_$]*""".r
    private val symbolic = """[!#%&*+\-/:<=>?@\\^|~]+""".r
    private val backticked = """`[^`\r\n]+`""".r
    private val keywords = Set(
      "abstract",
      "case",
      "catch",
      "class",
      "def",
      "do",
      "else",
      "enum",
      "export",
      "extends",
      "false",
      "final",
      "finally",
      "for",
      "forSome",
      "given",
      "if",
      "implicit",
      "import",
      "lazy",
      "match",
      "new",
      "null",
      "object",
      "override",
      "package",
      "private",
      "protected",
      "return",
      "sealed",
      "super",
      "then",
      "this",
      "throw",
      "trait",
      "true",
      "try",
      "type",
      "val",
      "var",
      "while",
      "with",
      "yield"
    )

    def from(value: String): Either[String, ScalaIdentifier] =
      nonEmpty(value, "Scala identifier").flatMap { refined =>
        val name = refined.value
        val valid =
          name match
            case plain()      => !keywords.contains(name)
            case symbolic()   => true
            case backticked() => true
            case _            => false
        if valid then Right(refined) else Left(s"invalid Scala identifier: $value")
      }

    extension (name: ScalaIdentifier) def value: String = nonEmptyValue(name)

  enum StructureDimension(val value: String):
    case Combined extends StructureDimension("combined")
    case Extends extends StructureDimension("extends")
    case MemberType extends StructureDimension("memberType")
    case Call extends StructureDimension("call")
    case Implicit extends StructureDimension("implicit")

  object StructureDimension:
    def from(value: String): Either[String, StructureDimension] =
      value.trim match
        case ""           => Right(Combined)
        case "combined"   => Right(Combined)
        case "extends"    => Right(Extends)
        case "memberType" => Right(MemberType)
        case "call"       => Right(Call)
        case "implicit"   => Right(Implicit)
        case other        => Left(s"invalid structure dimension: $other")

  enum StructureSort(val value: String):
    case Afferent extends StructureSort("afferent")
    case Efferent extends StructureSort("efferent")
    case Instability extends StructureSort("instability")
    case Layer extends StructureSort("layer")
    case Centrality extends StructureSort("centrality")
    case SccSize extends StructureSort("sccSize")

  object StructureSort:
    def from(value: String): Either[String, StructureSort] =
      value.trim match
        case ""            => Right(Afferent)
        case "afferent"    => Right(Afferent)
        case "efferent"    => Right(Efferent)
        case "instability" => Right(Instability)
        case "layer"       => Right(Layer)
        case "centrality"  => Right(Centrality)
        case "sccSize"     => Right(SccSize)
        case other         => Left(s"invalid structure sort: $other")

  enum SourceFormat(val value: String):
    case Annotated extends SourceFormat("annotated")
    case Compilable extends SourceFormat("compilable")
    case Plain extends SourceFormat("plain")

  object SourceFormat:
    def from(value: String): SourceFormat =
      value.trim match
        case "compilable" => Compilable
        case "plain"      => Plain
        case _            => Annotated

  enum SourceDetail(val value: String):
    case Terse extends SourceDetail("terse")
    case Full extends SourceDetail("full")

  object SourceDetail:
    def from(value: String): SourceDetail =
      value.trim match
        case "full" => Full
        case _      => Terse
