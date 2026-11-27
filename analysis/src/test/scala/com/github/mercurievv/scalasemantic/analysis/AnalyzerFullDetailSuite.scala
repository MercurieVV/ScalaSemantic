package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** `detail=full` (the `source_ranges` default) against hand-built synthetics shaped the way Scala 3
  * records them (cats-effect's ConsoleJVMSuite, #352): it must show at least what the terse view
  * shows, with the insertions at one call composed into the call the compiler made.
  */
class AnalyzerFullDetailSuite extends munit.FunSuite:

  private def range(sl: Int, sc: Int, el: Int, ec: Int) = Some(s.Range(sl, sc, el, ec))
  private def orig(sl: Int, sc: Int, el: Int, ec: Int) = s.OriginalTree(range(sl, sc, el, ec))
  private def id(sym: String) = s.IdTree(sym)
  private def ref(sym: String) = s.TypeRef(s.Type.Empty, sym, Nil)

  private def annotate(
      lines: IndexedSeq[String],
      synthetics: s.Synthetic*
  ): List[SourceAnnotation] =
    val doc = s.TextDocument(uri = "c.scala", text = lines.mkString("\n"), synthetics = synthetics)
    val uri = DocumentUri.from("c.scala").fold(fail(_), identity)
    Analyzer(SemanticIndex(Vector(doc)))
      .sourceAnnotations(uri, lines, SourceDetail.Full)
      .getOrElse(Nil)

  test("a conversion and its using clause on one span compose into one call"):
    // `a.show`: `toShow[Int](a)` and `a(using intShow)` are recorded separately on `a`.
    val lines = IndexedSeq("val text = a.show")
    val anns = annotate(
      lines,
      s.Synthetic(
        range(0, 11, 0, 12),
        s.ApplyTree(
          s.TypeApplyTree(id("cats/Show.ToShowOps#toShow()."), List(ref("scala/Int#"))),
          List(orig(0, 11, 0, 12))
        )
      ),
      s.Synthetic(
        range(0, 11, 0, 12),
        s.ApplyTree(orig(0, 11, 0, 12), List(id("cats/instances/intShow.")))
      )
    )
    assertEquals(anns.filter(_.kind == "full").map(_.text), List("toShow[Int](a)(using intShow)"))

  test("the receiver's own using clause stays inside the conversion's argument"):
    // `Resource.make(a)(b).void`: `make` takes `(using asyncForIO)` before `toFunctorOps` wraps it.
    val lines = IndexedSeq("x.make(a).void")
    val anns = annotate(
      lines,
      s.Synthetic(
        range(0, 0, 0, 9),
        s.ApplyTree(id("cats/Functor.ToFunctorOps#toFunctorOps()."), List(orig(0, 0, 0, 9)))
      ),
      s.Synthetic(
        range(0, 0, 0, 9),
        s.ApplyTree(orig(0, 0, 0, 9), List(id("cats/effect/IO.asyncForIO().")))
      ),
      s.Synthetic(
        range(0, 0, 0, 9),
        s.ApplyTree(orig(0, 0, 0, 9), List(id("cats/effect/Resource.asyncForResource().")))
      )
    )
    assertEquals(
      anns.filter(_.kind == "full").map(_.text),
      List("toFunctorOps(x.make(a)(using asyncForIO))(using asyncForResource)")
    )

  test("a multi-line call does not swallow the notes inside its block argument"):
    // `test("x") { … }(using generate)`: the whole test is one call; its body's notes must survive.
    val lines = IndexedSeq("""test("x") {""", "  pure(1).show", "  F.pure(2)", "}")
    val anns = annotate(
      lines,
      s.Synthetic(
        range(0, 0, 3, 1),
        s.ApplyTree(orig(0, 0, 3, 1), List(id("munit/Location.generate().")))
      ),
      s.Synthetic(range(1, 2, 1, 9), s.ApplyTree(orig(1, 2, 1, 9), List(id("x/Show.intShow.")))),
      s.Synthetic(range(2, 2, 2, 8), s.TypeApplyTree(orig(2, 2, 2, 8), List(ref("scala/Int#"))))
    )
    val byLine = anns.groupBy(_.line)
    assert(byLine.get(1).exists(_.exists(_.text.contains("(using intShow)"))), anns.toString)
    assert(byLine.get(2).exists(_.exists(_.text.contains("[Int]"))), anns.toString)

  test("a degraded re-statement of an argument's conversion is dropped"):
    // munit's `real("name") { … }`: besides the exact `testOptionsFromString("name")(using generate)`
    // on the argument, Scala 3 records `real` applied to the conversion minus its argument.
    val lines = IndexedSeq("real(", """  "name") {""", "}")
    val conv = "munit/TestOptionsConversions#testOptionsFromString()."
    val anns = annotate(
      lines,
      s.Synthetic(
        range(0, 0, 1, 9),
        s.ApplyTree(
          orig(0, 0, 0, 4),
          List(s.ApplyTree(id(conv), List(id("munit/Location.generate()."))))
        )
      ),
      s.Synthetic(range(1, 2, 1, 8), s.ApplyTree(id(conv), List(orig(1, 2, 1, 8)))),
      s.Synthetic(
        range(1, 2, 1, 8),
        s.ApplyTree(orig(1, 2, 1, 8), List(id("munit/Location.generate().")))
      )
    )
    val texts = anns.filter(_.kind == "full").map(_.text)
    assertEquals(texts, List("""testOptionsFromString("name")(using generate)"""))
