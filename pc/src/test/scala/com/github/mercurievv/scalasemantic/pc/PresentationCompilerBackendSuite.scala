package com.github.mercurievv.scalasemantic.pc

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import scala.meta.internal.semanticdb as s

/** Proves the spike: the PC regenerates SemanticDB for a buffer that does NOT compile (a type error
  * mid-file), and the result splices into [[SemanticIndex]] like any disk payload.
  */
class PresentationCompilerBackendSuite extends munit.FunSuite:

  // Deliberately broken: `val broken: Int = "oops"` does not typecheck. A disk compile would emit
  // NO semanticdb for this file; the PC must still describe the well-formed Animal/Dog above it.
  private val brokenSource =
    """package demo
      |
      |trait Animal:
      |  def name: String
      |
      |class Dog extends Animal:
      |  def name: String = "rex"
      |  def bark: Int = 1
      |
      |val broken: Int = "oops"
      |""".stripMargin

  // The buffer is a real file on disk (an edited source, stale vs. the last compile) under a
  // workspace dir — exactly the shape the PC expects. Written at init so it exists before any test.
  private val workspace = java.nio.file.Files.createTempDirectory("pc-spike").nn
  private val file = workspace.resolve("Demo.scala").nn
  private val uri = file.toUri
  java.nio.file.Files.writeString(file, brokenSource)

  // One backend for the suite; spinning a compiler instance is the expensive part.
  private val backend = PresentationCompilerBackend.fromCurrentJvm(workspace = Some(workspace))
  override def afterAll(): Unit = backend.close()

  test("PC emits SemanticDB for a buffer with a type error (disk compile would emit nothing)") {
    val doc = backend.semanticdb(uri, brokenSource)
    val syms = doc.symbols.map(_.symbol).toSet
    assert(syms.contains("demo/Animal#"), syms.toString)
    assert(syms.contains("demo/Dog#"), syms.toString)
    assert(syms.exists(_.startsWith("demo/Dog#name")), syms.toString)
  }

  test("regenerated buffer splices into a SemanticIndex and answers structural queries") {
    val index = backend.overlay(new SemanticIndex(Vector.empty), uri, brokenSource)

    // The index now knows Dog and its parent Animal — extracted from a non-compiling file.
    assertEquals(index.displayName("demo/Dog#"), "Dog")

    val dogSig = index.info("demo/Dog#").map(_.signature).collect { case c: s.ClassSignature => c }
    val parents = dogSig.toList.flatMap(_.parents).collect { case t: s.TypeRef => t.symbol }
    assert(parents.contains("demo/Animal#"), parents.toString)

    // And occurrences carry the in-memory uri, so location-based tools work on the buffer.
    assert(index.occurrences.exists((u, _) => u == uri.toString), "no occurrences tagged to buffer")
  }

  test("overlay replaces only the matching uri, leaving other documents intact") {
    val other = s.TextDocument(uri = "file:///mem/Other.scala")
    val base = new SemanticIndex(Vector(other))
    val merged = backend.overlay(base, uri, brokenSource)
    assert(merged.document("file:///mem/Other.scala").isDefined, "sibling document was dropped")
    assert(merged.document(uri.toString).exists(_.symbols.nonEmpty), "buffer doc not overlaid")
  }
