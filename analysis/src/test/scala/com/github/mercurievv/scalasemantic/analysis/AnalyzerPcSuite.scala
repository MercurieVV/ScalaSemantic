package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.pc.PresentationCompilerBackend
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

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

  test("withBuffer is a no-op without a PC backend") {
    val plain = new Analyzer(new SemanticIndex(Vector.empty))
    assert(plain.withBuffer(uri, source).findSymbol("Dog").isEmpty)
  }

  test("method_signature resolves on an uncompiled buffer via the PC overlay") {
    val live = az.withBuffer(uri, source)
    val bark = live.findSymbol("bark").headOption.getOrElse(fail("bark not found in overlay"))
    val sig = live.methodSignature(bark.symbol).getOrElse(fail("no signature for bark"))
    assertEquals(sig.returnType, "Int")
    assert(sig.rendered.contains("loud: Boolean"), sig.rendered)
  }

  test("type_at_position resolves on an uncompiled buffer via the PC overlay") {
    val live = az.withBuffer(uri, source)
    // Line 3 (0-based), the `bark` definition occurrence: `  def bark(...)`.
    val at = live.typeAtPosition(uri.toString, 3, 6).getOrElse(fail("nothing at bark position"))
    assertEquals(at.displayName, "bark")
    assertEquals(at.tpe, "Int")
  }
