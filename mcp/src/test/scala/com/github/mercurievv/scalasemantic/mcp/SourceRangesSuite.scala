package com.github.mercurievv.scalasemantic.mcp

import com.github.mercurievv.scalasemantic.analysis.Analyzer
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

import java.nio.file.Files
import java.nio.file.Path

/** `source_ranges` resolution/validation behavior that doesn't need a real SemanticDB index: a
  * literal file path resolves (or fails to resolve) purely by looking at disk, and a malformed
  * `query` is rejected before any file/symbol resolution happens. Actual rendering (which needs
  * `Analyzer.sourceAnnotations` against a real index) is covered by `SourceRangesFqnSuite`, which
  * dogfoods against this repo's own SemanticDB.
  */
class SourceRangesSuite extends munit.FunSuite:

  private def toolsIn(root: Path) =
    Mcp.toolsFor(Analyzer(SemanticIndex(Vector.empty)), root)

  private def call(root: Path, args: ujson.Value): ujson.Value =
    toolsIn(root)
      .find(_.name == "source_ranges")
      .getOrElse(fail("no source_ranges"))
      .run(args)

  test("reports found=false with an error for a target that does not resolve"):
    val root = Files.createTempDirectory("source-ranges-missing").nn
    val res = call(root, ujson.Obj("query" -> "DoesNotExist.scala[1-2]"))
    val entry = res("results").arr.head
    assertEquals(entry("target").str, "DoesNotExist.scala")
    assertEquals(entry("found").bool, false)
    assert(entry("error").str.nonEmpty)

  test("rejects a malformed query with an error rather than a partial result"):
    val root = Files.createTempDirectory("source-ranges-malformed").nn
    intercept[RuntimeException](call(root, ujson.Obj("query" -> "not a valid query")))
