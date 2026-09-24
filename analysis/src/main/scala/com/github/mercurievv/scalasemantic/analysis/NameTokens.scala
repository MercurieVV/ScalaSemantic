package com.github.mercurievv.scalasemantic.analysis

import scala.meta.*
import scala.util.Try

/** One name the source text mentions: its 0-based position, the text as written, and what kind of
  * name tree it came from. Syntax only — nothing here knows whether the name resolves. Pairing
  * these with the presentation compiler's SemanticDB occurrences is what makes an UNRESOLVED name
  * observable: the compiler emits an occurrence for every name it resolved, so a token with no
  * occurrence over it is a name it could not.
  */
final case class NameToken(line: Int, character: Int, name: String, kind: String, role: String)

object NameTokens:

  /** Every name token of `text` whose start position falls inside one of `ranges` (`[startLine,
    * endLineExclusive)`, 0-based); an empty `ranges` means the whole file. `None` when the text
    * does not parse — a syntax error is the caller's problem to report as such, not a list of zero
    * references.
    */
  def in(text: String, ranges: Seq[(Int, Int)]): Option[List[NameToken]] =
    Try(dialects.Scala3(text).parse[Source].get).toOption.map { tree =>
      val inRange: Position => Boolean =
        if ranges.isEmpty then _ => true
        else pos => ranges.exists((from, until) => pos.startLine >= from && pos.startLine < until)

      // `collect` over the whole tree, then filter by position: scalameta's traversal is cheap and
      // a tree-pruning walk would have to re-implement the containment logic for every node type.
      val tokens = tree.collect {
        case t: Type.Name if inRange(t.pos) =>
          NameToken(t.pos.startLine, t.pos.startColumn, t.value, "type", roleOf(t))
        case t: Term.Name if inRange(t.pos) =>
          NameToken(t.pos.startLine, t.pos.startColumn, t.value, kindOfTerm(t), roleOf(t))
        case t: Name.Indeterminate if inRange(t.pos) && !isImportAlias(t) =>
          NameToken(t.pos.startLine, t.pos.startColumn, t.value, "package", "use")
      }
      // Source order, as the traversal produced it: a caller joining these against compiler
      // occurrences walks both in position order, so inverting here would only surprise it.
      tokens.distinctBy(t => (t.line, t.character, t.name))
    }

  /** The NEW name a renaming import introduces: the `Q` of `import p.{Queue => Q}`, the `S` of
    * `import p.{Seq as S}`. It is a binding, not a reference — the compiler puts an occurrence on
    * the source name (`Queue`) and none on the alias — so a caller joining tokens against
    * occurrences would report every renaming import in ordinary code as an unresolved name. The
    * alias's later USE sites are untouched: they carry an occurrence for the symbol they alias, and
    * resolve normally. A `given` import (`import p.given`, `import p.{given Bar}`) needs no such
    * rule: it introduces no `Name.Indeterminate`, and the `Bar` of the second form is a real type
    * reference that resolves.
    */
  private def isImportAlias(t: Name.Indeterminate): Boolean =
    t.parent.exists {
      case r: Importee.Rename => r.rename eq t
      case _                  => false
    }

  /** A `Term.Name` that is the callee of an application is a method reference; anything else in a
    * term position is a value reference. Both are `term`-namespace names in SemanticDB, so the
    * distinction is for the reader (and for a policy that treats calls differently), not for
    * resolution. A name under a `Pkg`/`Importer` ref chain is a package reference, not a plain
    * term.
    */
  private def kindOfTerm(t: Term.Name): String =
    t.parent match
      case Some(p: Term.Apply) if p.fun eq t                                          => "method"
      case Some(p: Term.ApplyType) if p.fun eq t                                      => "method"
      case Some(p: Term.ApplyInfix) if p.op eq t                                      => "method"
      case Some(p: Term.Select) if (p.name eq t) && p.parent.exists(_.is[Term.Apply]) =>
        "method"
      case Some(_: Pkg) | Some(_: Importer)          => "package"
      case Some(p: Term.Select) if isPackageChain(p) => "package"
      case _                                         => "term"

  /** Whether a `Term.Select` sits inside a `Pkg` ref (`package foo.bar`) or an `Importer` qualifier
    * (`import foo.bar.Baz` — everything left of the last selector). Walking up through nested
    * `Term.Select`s handles multi-segment chains (`foo.bar.baz`).
    */
  private def isPackageChain(sel: Term.Select): Boolean =
    sel.parent match
      case Some(_: Pkg) | Some(_: Importer) => true
      case Some(p: Term.Select)             => isPackageChain(p)
      case _                                => false

  /** `definition` when the token IS the name being introduced (a def/val/var/class/object/type's
    * own name, a parameter's name, a pattern variable); `use` otherwise.
    */
  private def roleOf(t: Tree): String =
    t.parent match
      case Some(p: Defn.Def) if p.name eq t => "definition"
      case Some(p: Defn.Val)                => if p.pats.exists(_ eq t) then "definition" else "use"
      case Some(p: Defn.Var)                => if p.pats.exists(_ eq t) then "definition" else "use"
      case Some(p: Defn.Class) if p.name eq t  => "definition"
      case Some(p: Defn.Trait) if p.name eq t  => "definition"
      case Some(p: Defn.Object) if p.name eq t => "definition"
      case Some(p: Defn.Type) if p.name eq t   => "definition"
      case Some(p: Decl.Def) if p.name eq t    => "definition"
      case Some(p: Decl.Val) => if p.pats.exists(_ eq t) then "definition" else "use"
      case Some(p: Term.Param) if p.name eq t => "definition"
      case Some(p: Type.Param) if p.name eq t => "definition"
      case Some(p: Pat.Var) if p.name eq t    => "definition"
      case _                                  => "use"
