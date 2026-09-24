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

      // The few names an annotated type ascription costs their occurrence (see [[blindSpots]]); a
      // name there cannot be joined at all, so it is not reported rather than reported as a
      // hallucination. Everything else in the statement — a def's whole signature included — is
      // still checked.
      val blind = blindSpots(tree)
      val visible: Position => Boolean =
        pos => !blind.exists((from, until) => pos.start >= from && pos.start < until)

      // `collect` over the whole tree, then filter by position: scalameta's traversal is cheap and
      // a tree-pruning walk would have to re-implement the containment logic for every node type.
      val tokens = tree.collect {
        case t: Type.Name if inRange(t.pos) && visible(t.pos) && !isDerived(t) =>
          NameToken(t.pos.startLine, startColumn(text, t.pos), t.value, "type", roleOf(t))
        case t: Term.Name
            if inRange(t.pos) && visible(t.pos) && !isQuotedBinding(t) &&
              !isDerived(t) =>
          NameToken(t.pos.startLine, startColumn(text, t.pos), t.value, kindOfTerm(t), roleOf(t))
        case t: Name.Indeterminate
            if inRange(t.pos) && visible(t.pos) && !isImportAlias(t) && !isExportSelector(t) =>
          NameToken(t.pos.startLine, startColumn(text, t.pos), t.value, "package", "use")
      }
      // Source order, as the traversal produced it: a caller joining these against compiler
      // occurrences walks both in position order, so inverting here would only surprise it.
      tokens.distinctBy(t => (t.line, t.character, t.name))
    }

  /** The token's 0-based start column, advanced past a leading backtick. scalameta's position for a
    * backticked name (`` val `ok` ``) starts AT the backtick, while the compiler's DEFINITION
    * occurrence starts one character later, inside the quotes — so joining on the raw column misses
    * by one and reports every backticked definition as a hallucination. Uses are unaffected either
    * way: their occurrence spans the whole backticked name, so the advanced column is still inside
    * it. Backticked identifiers (`` `type` ``, Java interop names) are ordinary Scala.
    */
  private def startColumn(text: String, pos: Position): Int =
    if text.startsWith("`", pos.start) then pos.startColumn + 1 else pos.startColumn

  /** The character spans an annotated type ascription (`(o: @unchecked) match …`) costs their
    * compiler occurrence. Measured on Scala 3.8.4, the loss is narrow and shaped:
    *
    *   - the annotation itself (`@unchecked`) never has an occurrence, wherever it appears;
    *   - when the annotated expression is the RHS of a val/var, that statement additionally loses
    *     its DECLARED TYPE's occurrence and those of every pattern binder bound in the rhs (and of
    *     the uses of those binders): for `val w: Int = (o: @unchecked) match { case Some(n) => n }`
    *     the compiler emits `w`, `o` and `Some` and nothing for `Int`, `unchecked`, `n` or `n`'s
    *     use, while the same statement without the annotation is covered completely.
    *
    * Everything else is covered normally — a def's name, parameters, parameter types and result
    * type, a bare statement's binders, a lambda's or an `if` condition's — so nothing outside these
    * spans is suppressed. That matters: blanking a whole statement would hide a hallucinated
    * parameter type in `def f(p: Bogus): Int = (o: @unchecked) match …`, which is precisely what
    * this tool exists to catch. The residual cost is a hallucination spelled exactly like one of
    * those few names (a val's declared type, or a binder of its rhs) — a narrow false negative
    * traded for false positives on a compiler-sanctioned idiom.
    */
  private def blindSpots(tree: Tree): List[(Int, Int)] =
    tree.collect { case a: Term.Annotate => annotationSpans(a) ++ valRhsSpans(a) }.flatten

  /** The annotations of one annotated ascription (`@unchecked` in `(o: @unchecked)`). */
  private def annotationSpans(a: Term.Annotate): List[(Int, Int)] =
    a.children.collect { case m: Mod.Annot => span(m) }

  /** The extra names a val/var loses when its RHS carries the annotated ascription: the declared
    * type, and every binder the rhs introduces together with that binder's uses. Matched by NAME
    * inside the rhs, because a binder's use is an ordinary `Term.Name` with nothing to mark it — a
    * deliberately small net, so any other unresolved name in the rhs is still reported.
    *
    * Fires on exactly one shape: the rhs is a `Term.Match` whose scrutinee is `a`, as in
    * `val w: T = (o: @unchecked) match { ... }`. Measured on Scala 3.8.4, every neighbouring shape
    * is covered normally by the compiler and so stays reported — the annotation nested one level
    * deeper as a call argument (`id((o: @unchecked) match { ... })`) or inside one operand of an
    * unrelated infix expression; a block-wrapped or `if`/`else` rhs; and the annotation wrapping an
    * already-built match (`val w: T = (o match { ... }: @unchecked)`), where only the
    * bare-scrutinee form is blind.
    */
  private def valRhsSpans(a: Term.Annotate): List[(Int, Int)] =
    val stat = enclosingStat(a)
    val isValLike = stat.is[Defn.Val] || stat.is[Defn.Var]
    if !isValLike then Nil
    else
      val rhs = stat.children.reverse.collectFirst { case t: Term => t }
      val isAnnotatedScrutinee = rhs.exists {
        case m: Term.Match => m.expr eq a
        case _             => false
      }
      if !isAnnotatedScrutinee then Nil
      else
        val declared = stat.children.collectFirst { case t: Type => span(t) }.toList
        val bound = rhs.toList.flatMap { body =>
          val binders = body.collect { case p: Pat.Var => p.name.value }.toSet
          body.collect {
            case p: Pat.Var if binders.contains(p.name.value) => span(p.name)
            case t: Term.Name if binders.contains(t.value)    => span(t)
          }
        }
        declared ++ bound

  private def span(t: Tree): (Int, Int) = (t.pos.start, t.pos.end)

  /** The innermost enclosing STATEMENT of `t`: the ancestor that sits directly in a statement list
    * (a block, a template or package body, a case body, the source). Used only to recognise the
    * val/var shape above; climbing no further keeps that recognition to the one statement that
    * produced the annotation rather than to the whole enclosing method.
    */
  private def enclosingStat(t: Tree): Tree =
    t.parent match
      case Some(_: Term.Block | _: Template.Body | _: Pkg.Body | _: Source | _: Case) => t
      case Some(p)                                                                    =>
        enclosingStat(p)
      case None => t

  /** A name in a `derives` clause (`case class C(x: Int) derives Eq`, `derives q.Eq`). The compiler
    * emits no occurrence over it — the synthesized given is what it records — so joining would
    * report every `derives` in ordinary Scala 3 code as an unresolved name. The climb goes through
    * the whole type path, so a qualified clause's qualifier is skipped with its type.
    */
  private def isDerived(t: Tree): Boolean =
    def up(node: Tree): Boolean =
      node.parent match
        case Some(tmpl: Template) => tmpl.derives.exists(_ eq node)
        case Some(p: Type)        => up(p)
        case Some(p: Term.Select) => up(p)
        case _                    => false
    up(t)

  /** A named selector of an `export` clause (the `v` of `export i.v`). The compiler puts an
    * occurrence on the qualifier (`i`) and none on the selector — the export introduces a NEW
    * member here, it does not reference one — so it is a binding, like a renaming import's alias,
    * and reporting it would flag every named export in correct code. `export i.*` has no such name.
    */
  private def isExportSelector(t: Name.Indeterminate): Boolean =
    t.parent.exists {
      case n: Importee.Name => n.parent.exists(_.parent.exists(_.is[Export]))
      case _                => false
    }

  /** The spliced name of a macro quote (the `x` of `'x`). A quote binding is not a reference the
    * caller can act on, and the compiler emits no occurrence over it, so it would be reported as a
    * hallucination in ordinary Scala 3 macro code.
    */
  private def isQuotedBinding(t: Term.Name): Boolean =
    t.parent.exists(_.is[Term.QuotedMacroExpr])

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
    * own name, a given's or enum's or enum case's own name, a parameter's name, a pattern
    * variable); `use` otherwise. An `extension` group introduces no name of its own — the `def`s
    * inside it are ordinary `Defn.Def`s and are covered by that case.
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
      // Scala 3 definition forms: without these a given/enum/enum-case/extension declaration's own
      // name would be reported `use`, which is wrong about a whole family of declarations.
      case Some(p: Defn.Given) if p.name eq t      => "definition"
      case Some(p: Defn.GivenAlias) if p.name eq t => "definition"
      case Some(p: Defn.Enum) if p.name eq t       => "definition"
      case Some(p: Defn.EnumCase) if p.name eq t   => "definition"
      case _                                       => "use"
