package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** The join (spec 2026-09-24-diff-scoped-verification-design §1): scalameta's name tokens against
  * the presentation compiler's occurrences. What no single backend can answer is here — a name the
  * source mentions and the compiler never resolved.
  */
class ResolveReferencesSuite extends munit.FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration("120s")

  private val workspace = java.nio.file.Files.createTempDirectory("resolve-refs").nn
  private val file = workspace.resolve("Refs.scala").nn
  private val uri = file.toUri

  private val src =
    """package demo
      |
      |object Refs:
      |  val label: String = "x"
      |  def good: String = label.trim
      |  def bad: String = label.trimm
      |  def missing: String = nowhereAtAll
      |""".stripMargin

  java.nio.file.Files.writeString(file, src)

  private def refs(ranges: (Int, Int)*) =
    PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      val az = Analyzer(new SemanticIndex(Vector.empty), Some(backend))
      az.resolveReferences(uri, src, "Refs.scala", ranges).getOrElse(fail("no PC backend"))
    }

  test("a resolved stdlib reference carries its symbol and origin") {
    val r = refs((4, 5))
    val trim = r.references.find(_.name == "trim").getOrElse(fail(r.references.toString))
    assert(trim.resolved, trim.toString)
    assert(
      trim.symbol.exists(_.startsWith("scala/")) || trim.symbol.exists(_.startsWith("java/")),
      trim.toString
    )
    assertEquals(trim.origin, Some("stdlib"))
  }

  test("an unresolved member is reported with a near-miss suggestion") {
    val r = refs((5, 6))
    val bad = r.references.find(_.name == "trimm").getOrElse(fail(r.references.toString))
    assert(!bad.resolved, bad.toString)
    assertEquals(bad.symbol, None)
    assert(bad.suggestions.exists(_.contains("trim")), bad.suggestions.toString)
  }

  test("an unresolved free name is reported with no suggestion when nothing is close") {
    val r = refs((6, 7))
    val missing = r.references.find(_.name == "nowhereAtAll").getOrElse(fail(r.references.toString))
    assert(!missing.resolved, missing.toString)
    assertEquals(missing.suggestions, Nil)
  }

  test("references outside the ranges never appear") {
    val r = refs((6, 7))
    assert(!r.references.exists(_.name == "trimm"), r.references.toString)
    assert(!r.references.exists(_.name == "good"), r.references.toString)
  }

  test("a definition's own name is role=definition and resolves") {
    val r = refs((3, 4))
    val label = r.references.find(_.name == "label").getOrElse(fail(r.references.toString))
    assertEquals(label.role, "definition")
    assert(label.resolved, label.toString)
  }

  test("a syntax error is reported as an error, not as zero references") {
    val broken = "object X:\n  def f(: Int = 1\n"
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(new SemanticIndex(Vector.empty), Some(backend))
        .resolveReferences(uri, broken, "Refs.scala", Seq((0, 2)))
        .getOrElse(fail("no PC backend"))
    }
    assert(r.error.exists(_.nonEmpty), r.toString)
    assertEquals(r.references, Nil)
  }

  test("the 200-row bound is honoured and counted") {
    val many = (1 to 120).map(i => s"  val v$i: String = label").mkString("\n")
    val big = s"package demo\n\nobject Big:\n  val label: String = \"x\"\n$many\n"
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(new SemanticIndex(Vector.empty), Some(backend))
        .resolveReferences(uri, big, "Refs.scala", Nil)
        .getOrElse(fail("no PC backend"))
    }
    assertEquals(r.references.size, 200)
    assert(r.omitted > 0, r.omitted.toString)
  }

  test("a name the file itself defines is origin=project, not classpath"):
    // The file under analysis is precisely the one the index does NOT have — labelling its own
    // definitions `classpath` would be wrong about the tool's main scenario.
    val r = refs((3, 4))
    val label = r.references.find(_.name == "label").getOrElse(fail(r.references.toString))
    assertEquals(label.origin, Some("project"))
    val tpe = r.references.find(_.name == "String").getOrElse(fail(r.references.toString))
    assertEquals(tpe.origin, Some("stdlib"))

  // --- renaming and given imports -------------------------------------------

  private val importsSrc =
    """package demo
      |
      |import scala.collection.immutable.{Queue => Q, Seq as S}
      |import scala.math.Ordering.Implicits.given
      |
      |object Imports:
      |  val q: Q[Int] = Q.empty
      |  val s: S[Int] = S(1)
      |""".stripMargin

  test("a renaming or given import is not a hallucination"):
    val importsFile = workspace.resolve("Imports.scala").nn
    java.nio.file.Files.writeString(importsFile, importsSrc)
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(new SemanticIndex(Vector.empty), Some(backend))
        .resolveReferences(importsFile.toUri, importsSrc, "Imports.scala", Nil)
        .getOrElse(fail("no PC backend"))
    }
    assertEquals(r.error, None)
    // The alias is a binding, not a reference: it is not reported at all on the import line.
    assert(!r.references.exists(ref => ref.line == 2 && ref.name == "Q"), r.references.toString)
    assert(!r.references.exists(ref => ref.line == 2 && ref.name == "S"), r.references.toString)
    // Its use sites still are, and they resolve to what they alias.
    val use = r.references.find(ref => ref.line == 6 && ref.name == "Q").getOrElse(fail(r.toString))
    assert(use.resolved, use.toString)
    assert(use.symbol.exists(_.contains("Queue")), use.toString)
    // And nothing anywhere in the file — the given import included — is unresolved.
    assertEquals(r.references.filterNot(_.resolved), Nil)

  // --- suggestion ranking, against a NON-empty index ------------------------

  private def info(symbol: String, name: String): s.SymbolInformation =
    s.SymbolInformation(symbol = symbol, displayName = name)

  /** A hand-built index whose near-misses for `symbolz` disagree about order: the closest match
    * sorts LAST alphabetically, and three unsuggestable descriptors sort first.
    */
  private val ranked = new SemanticIndex(
    Vector(
      s.TextDocument(
        uri = "Synth.scala",
        symbols = Seq(
          info("zzz/symbol.", "symbol"), // distance 1 — the answer
          info("aaa/Symbol.", "Symbol"), // distance 2, but alphabetically first
          info("aab/Foo.f().(symbol)", "symbol"), // a parameter
          info("aac/Bar#[symbol]", "symbol"), // a type parameter
          info("symbol/", "symbol") // a package
        )
      )
    )
  )

  test("suggestions rank by distance and skip parameter, type-parameter and package symbols"):
    val typoSrc = "package demo\n\nobject Typo:\n  def z: String = symbolz\n"
    val typoFile = workspace.resolve("Typo.scala").nn
    java.nio.file.Files.writeString(typoFile, typoSrc)
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(ranked, Some(backend))
        .resolveReferences(typoFile.toUri, typoSrc, "Typo.scala", Nil)
        .getOrElse(fail("no PC backend"))
    }
    val typo = r.references.find(_.name == "symbolz").getOrElse(fail(r.references.toString))
    assert(!typo.resolved, typo.toString)
    assertEquals(typo.suggestions.headOption, Some("zzz/symbol."))
    assertEquals(typo.suggestions.count(_.endsWith(")")), 0, typo.suggestions.toString)
    assertEquals(typo.suggestions.count(_.endsWith("]")), 0, typo.suggestions.toString)
    assertEquals(typo.suggestions.count(_.endsWith("/")), 0, typo.suggestions.toString)

  // --- ordinary Scala 3 that must NOT be reported as hallucinated -----------
  // A `resolved: false` row blocks a turn, so each construct here is correct code that the join
  // reported as unresolved before this suite existed.

  private def refsOf(name: String, text: String) =
    val f = workspace.resolve(name).nn
    java.nio.file.Files.writeString(f, text)
    PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      Analyzer(new SemanticIndex(Vector.empty), Some(backend))
        .resolveReferences(f.toUri, text, name, Nil)
        .getOrElse(fail("no PC backend"))
    }

  test("a `derives` clause is not a hallucination"):
    val text = "package demo\n\ncase class Pt(x: Int, y: Int) derives CanEqual\n"
    val r = refsOf("Derives.scala", text)
    assertEquals(r.error, None)
    assertEquals(r.references.filterNot(_.resolved), Nil)

  test("a backticked definition is not a hallucination"):
    val text =
      "package demo\n\nobject Ticks:\n  val `ok`: Int = 1\n  def `type`: Int = 2\n  def use: Int = `ok` + `type`\n"
    val r = refsOf("Ticks.scala", text)
    assertEquals(r.error, None)
    assertEquals(r.references.filterNot(_.resolved), Nil)
    val defn = r.references.find(x => x.name == "ok" && x.line == 3).getOrElse(fail(r.toString))
    assertEquals(defn.role, "definition")
    assert(defn.resolved, defn.toString)

  test("a named export is not a hallucination"):
    val text = "package demo\n\nobject Exp:\n  val inner: String = \"x\"\n  export inner.trim\n"
    val r = refsOf("Exp.scala", text)
    assertEquals(r.error, None)
    assertEquals(r.references.filterNot(_.resolved), Nil)

  test("an annotated type ascription is not a hallucination"):
    // The PC covers this statement only partly (no occurrence for the val's `Int`, for `unchecked`
    // or for the pattern binder `n`), so joining inside it can only invent hallucinations.
    val text =
      """package demo
        |
        |object Unchecked:
        |  val o: Option[Int] = None
        |  val w: Int = (o: @unchecked) match { case Some(n) => n }
        |""".stripMargin
    val r = refsOf("Unchecked.scala", text)
    assertEquals(r.error, None)
    assertEquals(r.references.filterNot(_.resolved), Nil)

  test("an annotated ascription in a def body, a lambda or a bare statement is clean"):
    val text =
      """package demo
        |
        |object Shapes:
        |  val o: Option[Int] = None
        |  def bare: Unit = (o: @unchecked) match { case Some(n) => println(n) }
        |  def lam: List[Int] = List(o).map(x => (x: @unchecked) match { case Some(n) => n })
        |  def deep(q: String): Int =
        |    val inner: Int = (o: @unchecked) match { case Some(n) => n }
        |    inner
        |""".stripMargin
    val r = refsOf("Shapes.scala", text)
    assertEquals(r.error, None)
    assertEquals(r.references.filterNot(_.resolved), Nil)

  test("an annotated ascription never hides a hallucinated parameter type"):
    // The blind spot must not swallow the signature: blanking the whole statement would ship
    // `Bogus` silently, which is the failure this tool exists to prevent.
    val text =
      """package demo
        |
        |object Sig:
        |  val o: Option[Int] = None
        |  def f(p: Bogus): Int = (o: @unchecked) match { case Some(n) => n }
        |""".stripMargin
    val r = refsOf("Sig.scala", text)
    assertEquals(r.references.filterNot(_.resolved).map(_.name), List("Bogus"))

  test("an annotated ascription never hides a hallucination in the rhs"):
    val text =
      """package demo
        |
        |object Rhs:
        |  val o: Option[Int] = None
        |  val w: String = (o: @unchecked) match { case Some(n) => n.bogusMember }
        |""".stripMargin
    val r = refsOf("Rhs.scala", text)
    assertEquals(r.references.filterNot(_.resolved).map(_.name), List("bogusMember"))

  test(
    "an annotation nested one level inside the rhs still surfaces its hallucinated declared type"
  ):
    // The annotation is not the val's rhs itself (the rhs is the call `id(...)`), so the compiler
    // covers the declared type normally — a hallucination there must come back `resolved: false`,
    // not vanish from `references` the way a direct-rhs annotation's declared type does.
    val text =
      """package demo
        |
        |object Nested:
        |  val o: Option[Int] = None
        |  def id[A](x: A): A = x
        |  val w: Bogus = id((o: @unchecked) match { case Some(n) => n })
        |""".stripMargin
    val r = refsOf("Nested.scala", text)
    assertEquals(r.references.filterNot(_.resolved).map(_.name), List("Bogus"))

  test("two independent matches on one rhs, only one annotated: the unannotated binder resolves"):
    val text =
      """package demo
        |
        |object TwoMatches:
        |  val o: Option[Int] = None
        |  val p: Option[Int] = None
        |  val w: Int = ((o: @unchecked) match {case Some(n)=>n}) + (p match {case Some(m)=>m})
        |""".stripMargin
    val r = refsOf("TwoMatches.scala", text)
    assertEquals(r.error, None)
    assertEquals(r.references.filterNot(_.resolved), Nil)
    assert(r.references.exists(_.name == "m"), r.references.toString)

  test("a `var` with an ascribed rhs is not a hallucination, same as a `val`"):
    val text =
      """package demo
        |
        |object VarUnchecked:
        |  val o: Option[Int] = None
        |  var w: Int = (o: @unchecked) match { case Some(n) => n }
        |""".stripMargin
    val r = refsOf("VarUnchecked.scala", text)
    assertEquals(r.error, None)
    assertEquals(r.references.filterNot(_.resolved), Nil)

  test("a classpath symbol is origin=classpath, not stdlib or project"):
    val text = "package demo\n\nobject Cp:\n  val v: ujson.Value = ujson.Null\n"
    val r = refsOf("Cp.scala", text)
    assertEquals(r.error, None)
    assertEquals(r.references.filterNot(_.resolved), Nil)
    val value = r.references.find(_.name == "Value").getOrElse(fail(r.references.toString))
    assertEquals(value.origin, Some("classpath"))

  test("the bound never drops an unresolved row, however late it is"):
    // 120 resolved-heavy lines put the only hallucination well past the 200th token: capping in
    // source order would fill the quota with resolved rows and silently drop it.
    val filler = (1 to 120).map(i => s"  val v$i: String = label").mkString("\n")
    val text =
      s"package demo\n\nobject Late:\n  val label: String = \"x\"\n$filler\n  def tail: String = label.trimmm\n"
    val r = refsOf("Late.scala", text)
    assert(r.references.size >= Analyzer.MaxReferences, r.references.size.toString)
    assert(r.omitted > 0, r.omitted.toString)
    val late =
      r.references.find(_.name == "trimmm").getOrElse(fail("the late hallucination was dropped"))
    assert(!late.resolved, late.toString)
    // Only resolved rows are ever dropped.
    assertEquals(r.references.count(!_.resolved), 1)

  test("prefix `!` and infix `!=` / `==` on stdlib values are not hallucinations"):
    // A gated run (skreeep2, 2026-10-10) was blocked on `if solo && !state.soloed(name)` and on
    // `a != b`: the operators reached the join with no covering occurrence.
    val opsFile = workspace.resolve("Ops.scala").nn
    val ops =
      """package demo
        |
        |object Ops:
        |  def neg(b: Boolean, s: Set[String]): Boolean = !b && !s("x")
        |  def ne(a: Int, b: Int): Boolean = a != b || a == b
        |  def hid: Boolean = !nowhereFlag
        |""".stripMargin
    java.nio.file.Files.writeString(opsFile, ops)
    val r = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      val az = Analyzer(new SemanticIndex(Vector.empty), Some(backend))
      az.resolveReferences(opsFile.toUri, ops, "Ops.scala", Seq((3, 4)))
        .getOrElse(fail("no PC backend"))
    }
    val bad = r.references.filterNot(_.resolved)
    assert(bad.isEmpty, bad.toString)
    assert(r.references.exists(_.name == "b"), r.references.toString)
    // the operand of a prefix operator is still checked
    val hidden = PresentationCompilerBackend.useCurrentJvm(workspace = Some(workspace)) { backend =>
      val az = Analyzer(new SemanticIndex(Vector.empty), Some(backend))
      az.resolveReferences(opsFile.toUri, ops, "Ops.scala", Seq((5, 6)))
        .getOrElse(fail("no PC backend"))
    }
    assertEquals(hidden.references.filterNot(_.resolved).map(_.name), List("nowhereFlag"))
