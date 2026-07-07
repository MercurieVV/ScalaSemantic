package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Phase 4: the presentation-compiler second backend. Proves the position-local tools answer about
  * a buffer that is NOT in the disk index and does NOT compile — exactly the case the SemanticDB
  * index alone cannot serve (no clean compile ⇒ no payload for the file).
  */
class AnalyzerPcSuite extends munit.FunSuite:

  // A buffer whose tail does not typecheck; the PC must still describe Dog/bark above it.
  private val source =
    """package demo
      |
      |class Dog:
      |  def bark(loud: Boolean): Int = 1
      |
      |val broken: Int = "oops"
      |""".stripMargin

  private val workspace = java.nio.file.Files.createTempDirectory("analyzer-pc").nn
  private val file = workspace.resolve("Dog.scala").nn
  private val uri = file.toUri
  java.nio.file.Files.writeString(file, source)

  private val backend = PresentationCompilerBackend.fromCurrentJvm(workspace = Some(workspace))
  override def afterAll(): Unit = backend.close()

  // Empty disk index: everything the queries below see comes from the PC overlay alone.
  private val az = new Analyzer(new SemanticIndex(Vector.empty), Some(backend))

  private def meth(value: String): MethodSymbol =
    MethodSymbol.from(value).fold(fail(_), identity)
  private def doc(value: String): DocumentUri =
    DocumentUri.from(value).fold(fail(_), identity)
  private def pos(line: Int, character: Int): SourcePosition =
    SourcePosition.from(line, character).fold(fail(_), identity)
  private def range(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int) =
    SourceRange.from(startLine, startCharacter, endLine, endCharacter).fold(fail(_), identity)
  private def id(value: String): ScalaIdentifier =
    ScalaIdentifier.from(value).fold(fail(_), identity)

  test("withBuffer is a no-op without a PC backend") {
    val plain = new Analyzer(new SemanticIndex(Vector.empty))
    assert(plain.withBuffer(uri, source).findSymbol("Dog").isEmpty)
  }

  test("method_signature resolves on an uncompiled buffer via the PC overlay") {
    val live = az.withBuffer(uri, source)
    val bark = live.findSymbol("bark").headOption.getOrElse(fail("bark not found in overlay"))
    val sig = live.methodSignature(meth(bark.symbol)).getOrElse(fail("no signature for bark"))
    assertEquals(sig.returnType, "Int")
    assert(sig.rendered.contains("loud: Boolean"), sig.rendered)
  }

  test("withBuffer routes the PC backend by document uri") {
    val routedUris = java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val routed = new Analyzer(
      new SemanticIndex(Vector.empty),
      pcSelector = Some(docUri =>
        val _ = routedUris.updateAndGet(docUri :: _)
        Some(backend)
      )
    )

    val live = routed.withBuffer(uri, source, "module-a/src/main/scala/Dog.scala")
    assert(live.findSymbol("Dog").nonEmpty, "routed backend must still overlay the buffer")
    assertEquals(routedUris.get, List("module-a/src/main/scala/Dog.scala"))
  }

  test("type_at_position resolves on an uncompiled buffer via the PC overlay") {
    val live = az.withBuffer(uri, source)
    // Line 3 (0-based), the `bark` definition occurrence: `  def bark(...)`.
    val at =
      live.typeAtPosition(doc(uri.toString), pos(3, 6)).getOrElse(fail("nothing at bark position"))
    assertEquals(at.displayName, "bark")
    assertEquals(at.tpe, "Int")
  }

  // A method body with locals so extract-method's free-variable analysis has real structure.
  // Lines (0-based): 4 `val a`, 5 `val b`, 6 `val c`, 7 `c`.
  private val calc =
    """package demo
      |
      |object Calc:
      |  def run(n: Int): Int =
      |    val a: Int = n + 1
      |    val b: Int = a * 2
      |    val c: Int = b + a
      |    c
      |""".stripMargin
  private val calcFile = workspace.resolve("Calc.scala").nn
  java.nio.file.Files.writeString(calcFile, calc)

  test("extractMethodPlan derives params (free vars), returns (live-out), and the call site") {
    val live = az.bufferOnly(calcFile.toUri, calc, "Calc.scala").getOrElse(fail("no PC backend"))
    // Select lines 5..6 (`val b = a * 2` and `val c = b + a`); end is exclusive at (7, 0).
    val p = live
      .extractMethodPlan(doc("Calc.scala"), range(5, 0, 7, 0), id("compute"))
      .getOrElse(fail("expected an extract plan"))
    // `a` is read inside but defined above the selection → a parameter.
    assertEquals(p.parameters.map(_.name), List("a"))
    assertEquals(p.parameters.map(_.tpe), List("Int"))
    // `c` is defined inside and read on line 7 (after the selection) → the return; `b` is not.
    assertEquals(p.returns.map(_.name), List("c"))
    assertEquals(p.returnType, "Int")
    assertEquals(p.signature, "def compute(a: Int): Int")
    assertEquals(p.call, "val c = compute(a)")
    assertEquals(p.enclosingMethod.map(_.displayName), Some("run"))
  }

  test("bufferOnly (PC-only) queries the buffer alone; withBuffer (overlay) keeps the disk index") {
    // A disk index holding an unrelated document; the two routings differ on whether it shows.
    val diskDoc = s.TextDocument(
      uri = "Other.scala",
      symbols = Seq(s.SymbolInformation(symbol = "demo/Other#", displayName = "Other"))
    )
    val withDisk = new Analyzer(new SemanticIndex(Vector(diskDoc)), Some(backend))

    val pcOnly = withDisk.bufferOnly(uri, source, "Dog.scala").getOrElse(fail("no PC backend"))
    assert(pcOnly.findSymbol("Dog").nonEmpty, "PC-only must see the buffer")
    assert(pcOnly.findSymbol("Other").isEmpty, "PC-only must NOT consult the disk index")

    val overlaid = withDisk.withBuffer(uri, source, "Dog.scala")
    assert(overlaid.findSymbol("Dog").nonEmpty, "overlay sees the buffer")
    assert(overlaid.findSymbol("Other").nonEmpty, "overlay keeps the rest of the disk index")
  }
