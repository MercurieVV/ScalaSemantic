package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.{Clause, Contract, FileContracts}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.meta.*
import scala.util.Try

/** Every `def`'s Stainless contract — its `require`, `ensuring` and `decreases` clauses — with a
  * formatting-insensitive `shape` (spec 2026-09-27-stainless-vericoding-design §1). Syntax only,
  * the sibling of [[LiteralTokens]]: no presentation compiler, no classpath.
  *
  * What counts as a clause is Stainless's own placement: `ensuring` is the outermost call on the
  * def's body or on the body block's last expression (infix or method form); `require` and
  * `decreases` are TOP-LEVEL statements of the body block, in a leading run (the first non-clause
  * statement ends the run). Braces around a single block or a single `ensuring` call are
  * transparent. A `require` nested inside an expression is ordinary code, not a contract. Comments
  * and reformatting do not change shape: it is the hash of the clause's syntax tree structure,
  * excluding positions and comments.
  */
object ContractClauses:

  /** The contracted defs of `text`, in source order; `None` when the text does not parse. */
  def in(text: String): Option[List[Contract]] =
    Try(dialects.Scala3(text).parse[Source].get).toOption.map { tree =>
      val packageBlocks = tree.collect { case p: Pkg => p }.size > 1
      val raw = tree.collect { case d: Defn.Def => contractOf(d) }.flatten
      // A base key shared by several defs is rebuilt with kind-marked owners (`class:K`) and, in a
      // file with several package blocks, `pkg:` segments — so a companion class and object, or
      // same-named objects of two package blocks, no longer rely on source order. A key still
      // shared gets the typed parameter list (receiver first for an extension method); a key still
      // shared after that gets `#n`, n being the def's 1-based rank by source position.
      val marked = raw.map { (c, d, params) =>
        if raw.count(_._1.key == c.key) > 1 then
          (c.copy(key = markedKeyOf(d, packageBlocks)), params)
        else (c, params)
      }
      val typed = marked.map { (c, params) =>
        if marked.count(_._1.key == c.key) > 1 then
          c.copy(key = s"${c.key}(${params.mkString(", ")})")
        else c
      }
      numberDuplicates(typed)
    }

  /** `key#n` for every key shared by several defs, ranked by position in `cs` (source order) —
    * never by value equality, so identical contracts still get distinct numbers.
    */
  private def numberDuplicates(cs: List[Contract]): List[Contract] =
    val shared = cs.groupBy(_.key).collect { case (k, vs) if vs.size > 1 => k }.toSet
    if shared.isEmpty then cs
    else
      val numbered = cs.zipWithIndex.map { (c, i) =>
        if shared.contains(c.key) then
          c.copy(key = s"${c.key}#${cs.take(i).count(_.key == c.key) + 1}")
        else c
      }
      // A backticked name such as `f#1` could meet a generated key: number again until unique.
      numberDuplicates(numbered)

  /** [[in]] for one file, with a parse failure turned into `error`. */
  def forFile(uri: String, text: String): FileContracts =
    in(text) match
      case None       => FileContracts(uri, Nil, Some(s"$uri does not parse as Scala 3"))
      case Some(defs) => FileContracts(uri, defs, None)

  /** The def's contract plus its parameter types (used only to disambiguate overloads), or `None`
    * when it carries no clause.
    */
  private def contractOf(d: Defn.Def): Option[(Contract, Defn.Def, List[String])] =
    val (peeled, outerEnsuring) = peelEnsuring(transparent(d.body))
    val body = transparent(peeled)
    val stats = body match
      case b: Term.Block => b.stats
      case _             => Nil
    // Extract leading run: require AND decreases calls in any order, stop at first non-clause
    val leadingRunIdx = stats.indexWhere { s =>
      s match
        case a: Term.Apply => !isCall(a, "require") && !isCall(a, "decreases")
        case _             => true
    }
    val leadingRun = if leadingRunIdx >= 0 then stats.take(leadingRunIdx) else stats
    val requires = leadingRun.collect { case a: Term.Apply if isCall(a, "require") => clause(a) }
    val decreases = leadingRun.collectFirst {
      case a: Term.Apply if isCall(a, "decreases") => clause(a)
    }
    // `ensuring` on the whole body, else on the body block's last expression (the braceless
    // `require(…); val …; (expr).ensuring(…)` style), through any single-statement braces.
    val ensuringTermOpt = outerEnsuring.orElse(stats.lastOption.flatMap(lastEnsuring))
    // For ensuring: text and shape from just the lambda argument (postcondition)
    val ens = ensuringTermOpt.map(lambdaTerm => Clause(lambdaTerm.pos.text, shapeOf(lambdaTerm)))
    if requires.isEmpty && ens.isEmpty && decreases.isEmpty then None
    else
      val paramTypes = collectParamTypes(d)
      Some((Contract(keyOf(d), d.pos.startLine, requires, ens, decreases), d, paramTypes))

  /** Collect parameter types for collision resolution, including extension receiver if applicable.
    */
  private def collectParamTypes(d: Defn.Def): List[String] =
    // An extension method's parent is the ExtensionGroup (single def) or a block inside it.
    val extensionGroup = d.parent match
      case Some(e: Defn.ExtensionGroup) => Some(e)
      case Some(b: Term.Block)          =>
        b.parent.collect { case e: Defn.ExtensionGroup => e }
      case _ => None
    val extensionReceiver = extensionGroup.toList
      .flatMap(_.paramClauseGroup.toList)
      .flatMap(_.paramClauses.headOption.toList)
      .flatMap(_.values)
      .map(param)
    // Regular def parameters
    val defParams = d.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .map(param)
    extensionReceiver ++ defParams

  private def param(p: Term.Param): String =
    s"${p.name.value}: ${p.decltpe.map(_.syntax).getOrElse("?")}"

  /** A block whose single statement is itself a block or an `ensuring` call is transparent: the
    * braces add nothing Stainless sees.
    */
  private def transparent(t: Term): Term =
    t match
      case Term.Block(List(inner: Term.Block))                              => transparent(inner)
      case Term.Block(List(inner: Term)) if peelEnsuring(inner)._2.nonEmpty => inner
      case _                                                                => t

  /** The postcondition of an `ensuring` on `stat`, looking through single-statement blocks. */
  private def lastEnsuring(stat: Stat): Option[Term] =
    stat match
      case Term.Block(List(inner)) => lastEnsuring(inner)
      case t: Term                 => peelEnsuring(t)._2
      case _                       => None

  /** `body ensuring post` or `body.ensuring(post)` → (body, Some(lambda arg)). Handles `ensuring`
    * as infix, method call, or with type arguments like `ensuring[Int](...)`. Returns the body and
    * the lambda argument (postcondition) if found; text and shape are computed from the lambda
    * only.
    */
  private def peelEnsuring(t: Term): (Term, Option[Term]) =
    t match
      case ai: Term.ApplyInfix if ai.op.value == "ensuring" && ai.argClause.values.size == 1 =>
        ai.argClause.values.headOption match
          case Some(arg) => (ai.lhs, Some(arg))
          case None      => (ai.lhs, None)
      case a: Term.Apply =>
        a.fun match
          case Term.Select(qual, Term.Name("ensuring")) if a.argClause.values.size == 1 =>
            a.argClause.values.headOption match
              case Some(arg) => (qual, Some(arg))
              case None      => (qual, None)
          // Handle ensuring with type arguments: ensuring[T](...)
          case ait: Term.ApplyType =>
            ait.fun match
              case Term.Select(qual, Term.Name("ensuring")) if a.argClause.values.size == 1 =>
                a.argClause.values.headOption match
                  case Some(arg) => (qual, Some(arg))
                  case None      => (qual, None)
              case _ => (t, None)
          case _ => (t, None)
      case _ => (t, None)

  /** Check if this term is a call to `name`, either bare or qualified. Examples: `require(...)`,
    * `Predef.require(...)`, `scala.Predef.require(...)`.
    */
  private def isCall(a: Term.Apply, name: String): Boolean =
    a.fun match
      case Term.Name(n)                 => n == name
      case Term.Select(_, Term.Name(n)) => n == name
      case _                            => false

  private def clause(t: Tree): Clause = Clause(t.pos.text, shapeOf(t))

  /** Hash of the tree's fingerprint: a custom recursive serialization of structure ignoring
    * positions and comments. Braces wrapping a single statement are unwrapped at all levels. First
    * 16 hex chars of SHA-256.
    */
  private def shapeOf(t: Tree): String =
    val fp = fingerprint(t)
    MessageDigest
      .getInstance("SHA-256")
      .digest(fp.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** Recursive fingerprint of tree structure, excluding positions and comments. Single-statement
    * blocks are transparently unwrapped. For each node: emit productPrefix, then for atoms emit
    * value, then children recursively.
    */
  private def fingerprint(t: Tree): String =
    def visit(node: Tree): List[String] =
      // Unwrap single-EXPRESSION blocks at every level. A block holding one definition or import
      // keeps its braces: they scope the name (`{ val ok = …; ok }` vs `{ { val ok = … }; ok }`).
      val unwrapped = node match
        case Term.Block(List(inner: Term)) => inner
        case _                             => node

      unwrapped match
        case n: Name =>
          List(s"(Name ${escaped(n.value)})")
        case l: Lit =>
          val value = l match
            case lit: Lit.Int     => lit.value.toString
            case lit: Lit.Double  => lit.value.toString
            case lit: Lit.Float   => lit.value.toString
            case lit: Lit.Long    => lit.value.toString
            case lit: Lit.Boolean => lit.value.toString
            case _: Lit.Unit      => "()"
            case _: Lit.Null      => "null"
            case lit: Lit.String  => lit.value
            case _                => unwrapped.syntax
          // Emit the literal's type (productPrefix) with its value
          List(s"(${l.productPrefix} ${escaped(value)})")
        case _ =>
          val prefix = List(s"(${unwrapped.productPrefix}")
          val children = unwrapped.children.flatMap(visit)
          val suffix = List(")")
          prefix ++ children ++ suffix
    visit(t).mkString(" ")

  /** A name or literal value, length-prefixed so that no value can spell fingerprint syntax: the
    * string literal `"a\") (Lit.String \"b"` must not fingerprint like the two literals `"a", "b"`.
    */
  private def escaped(value: String): String = s"${value.length}:$value"

  /** Enclosing owner segments, then def's name. Owner segments: object, class, trait, enum, def,
    * val/var, given (`name`, or `given[<type>]` when anonymous). Extension receivers are added to
    * typed params instead. Packages are not segments.
    */
  private def keyOf(d: Defn.Def): String = keyWith(d, ownerName)

  /** The def's name after the `segment` of every enclosing tree that has one, outermost first. */
  private def keyWith(d: Defn.Def, segment: Tree => Option[String]): String =
    val owners = Iterator
      .iterate(d.parent)(_.flatMap(_.parent))
      .takeWhile(_.isDefined)
      .flatten
      .flatMap(segment)
      .toList
      .reverse
    (owners ++ List(d.name.value)).mkString(".")

  private def ownerName(p: Tree): Option[String] = p match
    case o: Defn.Object => Some(o.name.value)
    case c: Defn.Class  => Some(c.name.value)
    case t: Defn.Trait  => Some(t.name.value)
    case e: Defn.Enum   => Some(e.name.value)
    case f: Defn.Def    => Some(f.name.value)
    case g: Defn.Given  =>
      Some(givenSegment(g.name, g.templ.inits.map(_.tpe.syntax).mkString(" with ")))
    case g: Defn.GivenAlias => Some(givenSegment(g.name, g.decltpe.syntax))
    case v: Defn.Val        =>
      v.pats.headOption.flatMap {
        case Pat.Var(n) => Some(n.value)
        case _          => None
      }
    case v: Defn.Var =>
      v.pats.headOption.flatMap {
        case Pat.Var(n) => Some(n.value)
        case _          => None
      }
    case _ => None
  // A named given is its name; an anonymous one is `given[<its type>]`.
  private def givenSegment(name: Name, tpe: => String): String =
    if name.value.isEmpty then s"given[$tpe]" else name.value

  /** [[keyOf]] for a def whose key collides: `class:`/`trait:`/`enum:` mark those owners (objects,
    * givens, defs and vals stay unmarked), `pkgobj:` a package object, and — when the file has more
    * than one package block — every enclosing package is a `pkg:<ref>` segment.
    */
  private def markedKeyOf(d: Defn.Def, packageBlocks: Boolean): String =
    def marked(p: Tree): Option[String] = p match
      case c: Defn.Class            => Some(s"class:${c.name.value}")
      case t: Defn.Trait            => Some(s"trait:${t.name.value}")
      case e: Defn.Enum             => Some(s"enum:${e.name.value}")
      case o: Pkg.Object            => Some(s"pkgobj:${o.name.value}")
      case pk: Pkg if packageBlocks => Some(s"pkg:${pk.ref.syntax}")
      case other                    => ownerName(other)
    keyWith(d, marked)
