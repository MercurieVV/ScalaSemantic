package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Deterministic unit tests for [[Analyzer]] driven against small hand-built in-memory indexes (no
  * on-disk dogfooding, so they are stable under mutation and belong in stryker4s.conf's filter —
  * unlike AnalyzerSuite, which loads the live `fromProject(".")` index). Each builds exactly the
  * symbols/occurrences a query needs and asserts the orchestration branches (hierarchy
  * linearization, overload grouping, implicit-list detection, given resolution, call-path BFS).
  */
class AnalyzerCoreSuite extends munit.FunSuite:

  private val P = "com/x/"

  private def tref(sym: String): s.Type = s.TypeRef(s.Type.Empty, sym, Nil)
  private val ObjectT = tref("java/lang/Object#")
  private val IntT = tref("scala/Int#")

  private def info(
      symbol: String,
      kind: s.SymbolInformation.Kind,
      displayName: String,
      signature: s.Signature = s.NoSignature,
      properties: Int = 0
  ): s.SymbolInformation =
    s.SymbolInformation(
      symbol = symbol,
      kind = kind,
      displayName = displayName,
      signature = signature,
      properties = properties
    )

  private def cls(symbol: String, display: String, parents: List[s.Type], decls: List[String]) =
    info(
      symbol,
      s.SymbolInformation.Kind.CLASS,
      display,
      s.ClassSignature(None, parents, s.Type.Empty, Some(s.Scope(symlinks = decls)))
    )

  private def meth(symbol: String, display: String, sig: s.Signature = s.NoSignature) =
    info(symbol, s.SymbolInformation.Kind.METHOD, display, sig)

  private def param(symbol: String, display: String, implicitly: Boolean) =
    info(
      symbol,
      s.SymbolInformation.Kind.PARAMETER,
      display,
      s.ValueSignature(IntT),
      if implicitly then s.SymbolInformation.Property.IMPLICIT.value else 0
    )

  private def occ(symbol: String, role: s.SymbolOccurrence.Role, line: Int) =
    s.SymbolOccurrence(Some(s.Range(line, 0, line, 1)), symbol, role)

  import s.SymbolOccurrence.Role.{DEFINITION, REFERENCE}

  // --- hierarchy fixture: Puppy <: Dog <: Animal ----------------------------

  private val animalCls =
    cls(s"${P}Animal#", "Animal", List(ObjectT), List(s"${P}Animal#greetM()."))
  private val dogCls =
    cls(s"${P}Dog#", "Dog", List(tref(s"${P}Animal#")), List(s"${P}Dog#barkM()."))
  private val puppyCls = cls(s"${P}Puppy#", "Puppy", List(tref(s"${P}Dog#")), Nil)
  private val greetM = meth(s"${P}Animal#greetM().", "greetM")
  private val barkM = meth(s"${P}Dog#barkM().", "barkM")

  private def analyzer(symbols: s.SymbolInformation*)(occs: s.SymbolOccurrence*): Analyzer =
    Analyzer(
      SemanticIndex(
        Vector(
          s.TextDocument(uri = "x.scala", symbols = symbols.toVector, occurrences = occs.toVector)
        )
      )
    )

  private def tpe(v: String) = TypeSymbol.from(v).fold(fail(_), identity)
  private def method(v: String) = MethodSymbol.from(v).fold(fail(_), identity)

  test("classHierarchy: parents, full linearization, known subtypes"):
    val az = analyzer(animalCls, dogCls, puppyCls, greetM, barkM)()
    val h = az.classHierarchy(tpe(s"${P}Puppy#")).getOrElse(fail("no hierarchy"))
    assertEquals(h.parents.map(_.displayName), List("Dog"))
    assertEquals(h.linearization.map(_.displayName), List("Dog", "Animal", "Object"))
    assertEquals(
      az.classHierarchy(tpe(s"${P}Animal#")).get.knownSubtypes.map(_.displayName),
      List("Dog")
    )

  test("members: declared on the type, inherited from the linearization"):
    val az = analyzer(animalCls, dogCls, puppyCls, greetM, barkM)()
    val m = az.members(tpe(s"${P}Dog#")).getOrElse(fail("no members"))
    assertEquals(m.declared.map(_.displayName), List("barkM"))
    assertEquals(m.inherited.map(_.displayName), List("greetM"))

  // --- method-signature implicit-list detection -----------------------------

  private def plist(params: s.SymbolInformation*) = s.Scope(hardlinks = params.toVector)
  private def methodSig(symbol: String, display: String, lists: Seq[s.Scope]) =
    meth(symbol, display, s.MethodSignature(None, lists, IntT))

  test("methodSignature flags a list implicit only when non-empty and all-implicit"):
    val all =
      methodSig(s"${P}C#a().", "a", Seq(plist(param(s"${P}C#a().(e)", "e", implicitly = true))))
    val none =
      methodSig(s"${P}C#b().", "b", Seq(plist(param(s"${P}C#b().(x)", "x", implicitly = false))))
    val mixed = methodSig(
      s"${P}C#c().",
      "c",
      Seq(
        plist(
          param(s"${P}C#c().(a)", "a", implicitly = true),
          param(s"${P}C#c().(b)", "b", implicitly = false)
        )
      )
    )
    val az = analyzer(all, none, mixed)()
    assertEquals(
      az.methodSignature(method(s"${P}C#a().")).get.parameterLists.map(_.isImplicit),
      List(true)
    )
    assertEquals(
      az.methodSignature(method(s"${P}C#b().")).get.parameterLists.map(_.isImplicit),
      List(false)
    )
    assertEquals(
      az.methodSignature(method(s"${P}C#c().")).get.parameterLists.map(_.isImplicit),
      List(false)
    )

  // --- find-overloads -------------------------------------------------------

  test("findOverloads groups by owner AND name (not same-owner/-name alone)"):
    val put0 = methodSig(s"${P}Box#put().", "put", Nil)
    val put1 = methodSig(s"${P}Box#put(+1).", "put", Nil)
    val get0 = methodSig(s"${P}Box#get().", "get", Nil) // same owner, different name
    val otherPut = methodSig("com/y/Other#put().", "put", Nil) // same name, different owner
    val az = analyzer(put0, put1, get0, otherPut)()
    val o = az.findOverloads(method(s"${P}Box#put()."))
    assertEquals(o.name, "put")
    assertEquals(o.overloads.map(_.symbol), List(s"${P}Box#put().", s"${P}Box#put(+1)."))
    assert(o.overloads.forall(_.displayName == "put"))

  // --- resolve-implicits ----------------------------------------------------

  private val showCls = cls(s"${P}Show#", "Show", List(ObjectT), Nil)
  private def givenObj(symbol: String, display: String) =
    info(
      symbol,
      s.SymbolInformation.Kind.OBJECT,
      display,
      s.ClassSignature(None, List(tref(s"${P}Show#"), ObjectT), s.Type.Empty, None),
      s.SymbolInformation.Property.IMPLICIT.value
    )

  test("resolveImplicits: candidate is not from an explicit import; chosen iff unique"):
    val one = analyzer(showCls, givenObj(s"${P}intShow.", "intShow"))()
    val r = one.resolveImplicits(tpe(s"${P}Show#"))
    assertEquals(r.candidates.map(_.target.displayName), List("intShow"))
    assert(!r.candidates.head.fromExplicitImport, "synthesized, not an explicit import")
    assertEquals(r.chosen.map(_.displayName), Some("intShow"))
    val two = analyzer(
      showCls,
      givenObj(s"${P}intShow.", "intShow"),
      givenObj(s"${P}listShow.", "listShow")
    )()
    assertEquals(two.resolveImplicits(tpe(s"${P}Show#")).chosen, None)

  // --- call-path BFS --------------------------------------------------------

  test("callPath walks definition->reference edges; self path is the single node"):
    // a (def) -> references b; b (def) -> references c; c (def)
    val a = meth(s"${P}M#a().", "a")
    val b = meth(s"${P}M#b().", "b")
    val c = meth(s"${P}M#c().", "c")
    val az = analyzer(a, b, c)(
      occ(s"${P}M#a().", DEFINITION, 0),
      occ(s"${P}M#b().", REFERENCE, 1),
      occ(s"${P}M#b().", DEFINITION, 2),
      occ(s"${P}M#c().", REFERENCE, 3),
      occ(s"${P}M#c().", DEFINITION, 4)
    )
    val p = az.callPath(method(s"${P}M#a()."), method(s"${P}M#c()."))
    assertEquals(p.path.map(_.displayName), List("a", "b", "c"))
    assertEquals(
      p.edges.map(e => e.from.displayName -> e.to.displayName),
      List("a" -> "b", "b" -> "c")
    )
    val self = az.callPath(method(s"${P}M#a()."), method(s"${P}M#a()."))
    assertEquals(self.path.map(_.displayName), List("a"))
    val unreachable = az.callPath(method(s"${P}M#c()."), method(s"${P}M#a()."))
    assertEquals(unreachable.path, Nil)
