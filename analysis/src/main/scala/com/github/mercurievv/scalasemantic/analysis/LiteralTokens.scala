package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.{Enclosing, FileLiterals, Literal}
import scala.meta.*
import scala.util.Try

/** Every literal the source text contains, with a normalised value and its nearest enclosing
  * definition (spec 2026-09-25-purrism-loop-and-memorisation-design §1). Syntax only — the sibling
  * of [[NameTokens]], and like it needs no presentation compiler and no classpath.
  *
  * No policy lives here: which files are tests, what counts as trivial and what overlap is
  * suspicious are the caller's decisions, so `0` and `1` are returned like any other literal.
  */
object LiteralTokens:

  val MaxPerFile: Int = 2000

  /** Every literal of `text` whose start line falls inside one of `ranges` (`[startLine,
    * endLineExclusive)`, 0-based); an empty `ranges` means the whole file. `None` when the text
    * does not parse.
    */
  def in(text: String, ranges: Seq[(Int, Int)]): Option[List[Literal]] =
    Try(dialects.Scala3(text).parse[Source].get).toOption.map { tree =>
      val inRange: Position => Boolean =
        if ranges.isEmpty then _ => true
        else pos => ranges.exists((from, until) => pos.startLine >= from && pos.startLine < until)
      tree
        .collect {
          // A negative literal: report it once, with its sign, whichever shape the parser chose.
          case u @ Term.ApplyUnary(Term.Name("-"), l: Lit) if inRange(u.pos) =>
            literal(u, l, negative = true)
          case l: Lit if inRange(l.pos) && !isNegatedOperand(l) && !isEmptyInterpolationPart(l) =>
            literal(l, l, negative = false)
        }
        .flatten
        .sortBy(l => (l.line, l.character))
    }

  /** [[in]] for one file, with the output bound applied and a parse failure turned into `error`. */
  def forFile(uri: String, text: String, ranges: Seq[(Int, Int)]): FileLiterals =
    in(text, ranges) match
      case None      => FileLiterals(uri, Nil, 0, Some(s"$uri does not parse as Scala 3"))
      case Some(all) =>
        FileLiterals(uri, all.take(MaxPerFile), (all.size - MaxPerFile).max(0), None)

  /** An interpolation has one more part than it has arguments, so `s"note $n"` carries an empty
    * trailing part the author never wrote: structure, not a literal. An EXPLICIT `""` is not an
    * interpolation part and is still reported.
    */
  private def isEmptyInterpolationPart(l: Lit): Boolean =
    l match
      case Lit.String("") => l.parent.exists(_.is[Term.Interpolate])
      case _              => false

  private def isNegatedOperand(l: Lit): Boolean =
    l.parent.exists {
      case Term.ApplyUnary(Term.Name("-"), arg) => arg eq l
      case _                                    => false
    }

  /** `at` is the tree whose position is reported (the unary minus, for a negative literal); `lit`
    * is the literal itself.
    */
  private def literal(at: Tree, lit: Lit, negative: Boolean): Option[Literal] =
    kindAndValue(lit).map { (kind, value) =>
      val signed = if negative then s"-$value" else value
      Literal(at.pos.startLine, at.pos.startColumn, kind, signed, enclosingOf(at))
    }

  private def kindAndValue(lit: Lit): Option[(String, String)] =
    lit match
      case Lit.Int(v)    => Some("int" -> v.toString)
      case Lit.Long(v)   => Some("long" -> v.toString)
      case d: Lit.Double => Some("double" -> d.value.toString)
      case f: Lit.Float  => Some("float" -> f.value.toString)
      case Lit.Char(v)   => Some("char" -> v.toString)
      case Lit.String(v) => Some("string" -> v)
      case _             => None // Boolean, Null, Unit, Symbol: no signal

  private def enclosingOf(t: Tree): Option[Enclosing] =
    Iterator
      .iterate(t.parent)(_.flatMap(_.parent))
      .takeWhile(_.isDefined)
      .flatten
      .collectFirst {
        case d: Defn.Def    => Enclosing("def", d.name.value, d.pos.startLine)
        case d: Defn.Val    => Enclosing("val", patName(d.pats), d.pos.startLine)
        case d: Defn.Var    => Enclosing("var", patName(d.pats), d.pos.startLine)
        case d: Defn.Object => Enclosing("object", d.name.value, d.pos.startLine)
        case d: Defn.Class  => Enclosing("class", d.name.value, d.pos.startLine)
        case d: Defn.Trait  => Enclosing("trait", d.name.value, d.pos.startLine)
        case d: Defn.Enum   => Enclosing("enum", d.name.value, d.pos.startLine)
        case d: Defn.Given  => Enclosing("given", givenName(d.name), d.pos.startLine)
      }

  private def patName(pats: List[Pat]): String =
    pats.collectFirst { case Pat.Var(n) => n.value }.getOrElse("<pattern>")

  private def givenName(n: Name): String =
    if n.value.isEmpty then "<anonymous>" else n.value
