package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path

/** `annotated_source`'s WRITE mode: pass `write` (edited, possibly `SEM:...:SEM`-annotated text) to
  * strip the sentinel blocks and persist the result to `uri`, instead of reading. No real
  * SemanticDB index is needed here — write mode never calls into `Analyzer.sourceAnnotations`.
  */
class AnnotatedSourceWriteSuite extends munit.FunSuite:

  private def toolsIn(root: Path) =
    Mcp.toolsFor(Analyzer(SemanticIndex(Vector.empty)), root)

  private def writeFile(root: Path, name: String, content: String): Unit =
    val _ = Files.writeString(root.resolve(name), content)

  private def call(root: Path, args: ujson.Value): ujson.Value =
    toolsIn(root)
      .find(_.name == "annotated_source")
      .getOrElse(fail("no annotated_source"))
      .run(args)

  test("write mode strips SEM:...:SEM blocks and persists the clean result"):
    val root = Files.createTempDirectory("ss-annotated-write").nn
    writeFile(root, "Foo.scala", "object Foo:\n  def bar = 1\n")
    val edited = "object Foo:\n  def bar = 1 /*SEM:type=Int:SEM*/\n"
    val res = call(root, ujson.Obj("uri" -> "Foo.scala", "write" -> edited))
    assertEquals(res("written").bool, true)
    assertEquals(Files.readString(root.resolve("Foo.scala")), "object Foo:\n  def bar = 1\n")

  test("write mode preserves a real trailing comment, only the SEM block is removed"):
    val root = Files.createTempDirectory("ss-annotated-write-comment").nn
    val original = "  def bar = 1 // real comment\n"
    writeFile(root, "Foo.scala", original)
    val edited = "  def bar = 1 // real comment /*SEM:type=Int:SEM*/\n"
    val _ = call(root, ujson.Obj("uri" -> "Foo.scala", "write" -> edited))
    assertEquals(Files.readString(root.resolve("Foo.scala")), original)

  // `McpToolsSupport.sha256Hex` is the exact function write mode checks `baseHash` against — used
  // here directly rather than via a read, since annotated_source's READ branch needs a real
  // SemanticDB index (out of scope for these write-mode-only tests, which use an empty index).
  private def hashOf(root: Path, name: String): String =
    McpToolsSupport.sha256Hex(Files.readAllBytes(root.resolve(name)).nn)

  test("baseHash computed from the file's current content is accepted by a write"):
    val root = Files.createTempDirectory("ss-annotated-write-hash").nn
    writeFile(root, "Foo.scala", "object Foo:\n  def bar = 1\n")
    val hash = hashOf(root, "Foo.scala")
    assert(hash.nonEmpty, hash)
    val res =
      call(
        root,
        ujson.Obj(
          "uri" -> "Foo.scala",
          "write" -> "object Foo:\n  def bar = 2\n",
          "baseHash" -> hash
        )
      )
    assertEquals(res("written").bool, true)
    assertEquals(Files.readString(root.resolve("Foo.scala")), "object Foo:\n  def bar = 2\n")

  test("write is rejected when baseHash no longer matches the file on disk (concurrent edit)"):
    val root = Files.createTempDirectory("ss-annotated-write-conflict").nn
    writeFile(root, "Foo.scala", "object Foo:\n  def bar = 1\n")
    val staleHash = hashOf(root, "Foo.scala")
    // Someone else changes the file after the hash was taken, before the write lands.
    writeFile(root, "Foo.scala", "object Foo:\n  def bar = 999 // someone else's edit\n")
    intercept[RuntimeException]:
      call(
        root,
        ujson.Obj(
          "uri" -> "Foo.scala",
          "write" -> "object Foo:\n  def bar = 2\n",
          "baseHash" -> staleHash
        )
      )
    // The concurrent edit must survive untouched — the rejected write must not have landed.
    assertEquals(
      Files.readString(root.resolve("Foo.scala")),
      "object Foo:\n  def bar = 999 // someone else's edit\n"
    )

  // Only `SEM:...:SEM` blocks are strippable. A buffer read in any other annotated form carries
  // `⟹` notes (and, in the default format, a line-number gutter) that would be persisted verbatim
  // and corrupt the source — so such a write is refused rather than applied.
  test("write is refused when the buffer still carries ⟹ notes from a non-sentinel read"):
    val root = Files.createTempDirectory("ss-annotated-write-notes").nn
    val original = "object Foo:\n  def bar = 1\n"
    writeFile(root, "Foo.scala", original)
    intercept[RuntimeException]:
      call(
        root,
        ujson.Obj("uri" -> "Foo.scala", "write" -> "object Foo:\n  def bar = 1 // ⟹ : Int\n")
      )
    assertEquals(Files.readString(root.resolve("Foo.scala")), original)

  test("write is refused when the buffer still carries the read-only line-number gutter"):
    val root = Files.createTempDirectory("ss-annotated-write-gutter").nn
    val original = "object Foo:\n  def bar = 1\n"
    writeFile(root, "Foo.scala", original)
    intercept[RuntimeException]:
      call(
        root,
        ujson.Obj("uri" -> "Foo.scala", "write" -> "    1  object Foo:\n    2    def bar = 1\n")
      )
    assertEquals(Files.readString(root.resolve("Foo.scala")), original)

  test("write without baseHash succeeds even if the file changed since it was last read"):
    val root = Files.createTempDirectory("ss-annotated-write-nohash").nn
    writeFile(root, "Foo.scala", "object Foo:\n  def bar = 1\n")
    val res = call(root, ujson.Obj("uri" -> "Foo.scala", "write" -> "object Foo:\n  def bar = 3\n"))
    assertEquals(res("written").bool, true)
    assertEquals(Files.readString(root.resolve("Foo.scala")), "object Foo:\n  def bar = 3\n")
