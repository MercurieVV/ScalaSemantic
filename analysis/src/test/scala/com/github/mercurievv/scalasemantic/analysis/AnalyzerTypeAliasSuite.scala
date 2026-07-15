package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.*
import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import upickle.default.write

import java.nio.file.Files
import java.nio.file.Paths

/** Dogfood test for `Kind.TYPE` (type-alias / opaque-type) signature rendering in
  * `document_outline`.
  *
  * Runs against this repository's own SemanticDB and pins the rendered signature of every type
  * declaration in two real files:
  *   - `GraphMetrics.scala` — the transparent alias `type Graph = Map[String, Set[String]]`.
  *   - `InputTypes.scala` — the opaque types (`SemanticDbSymbol`, `DocumentUri`, ...) plus the
  *     refined aliases (`NonNegativeInt`, `PositiveInt`, `NonEmptyString`).
  *
  * Transparent/refined aliases carry their RHS (`= T`); opaque types hide their RHS in SemanticDB
  * and surface as `>: Nothing <: Any`. The golden map captures exactly what the compiler emits.
  *
  * Golden workflow matches [[AnalyzerGoldenSuite]]: first run writes the file, later runs compare.
  */
class AnalyzerTypeAliasSuite extends munit.FunSuite:

  private val az = Analyzer(SemanticIndex.fromProject("."))

  private val files = List(
    "analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/graph/GraphMetrics.scala",
    "analysis/src/main/scala/com/github/mercurievv/scalasemantic/model/InputTypes.scala"
  )

  private val goldenPath =
    Paths.get("analysis/src/test/resources/golden/type_alias_signatures.json")

  private def typeSignatures(entries: List[OutlineEntry]): List[(String, String)] =
    entries.flatMap { e =>
      val self = if e.kind.toString == "Type" then List(e.name -> e.signature) else Nil
      self ++ typeSignatures(e.children)
    }

  test("type-alias / opaque-type outline signatures match golden reference"):
    val actualMap = files.flatMap { f =>
      val entries = DocumentUri
        .from(f)
        .toOption
        .flatMap(az.outline)
        .getOrElse(fail(s"not indexed: $f"))
      typeSignatures(entries).map((n, sig) => s"${f.split('/').last}#$n" -> sig)
    }.sorted
    val actual = write(actualMap, indent = 2)

    if Files.exists(goldenPath) then assertEquals(actual, Files.readString(goldenPath))
    else
      val _ = Files.createDirectories(goldenPath.getParent)
      val _ = Files.writeString(goldenPath, actual)

  test("transparent alias Graph renders its RHS"):
    val graph = DocumentUri
      .from(files.head)
      .toOption
      .flatMap(az.outline)
      .toList
      .flatMap(typeSignatures)
      .collectFirst { case ("Graph", sig) => sig }
    assertEquals(graph, Some("= Map[String, Set[String]]"))

  test("every declared type carries a non-empty signature"):
    val sigs = files.flatMap { f =>
      DocumentUri.from(f).toOption.flatMap(az.outline).toList.flatMap(typeSignatures)
    }
    assert(sigs.nonEmpty, "expected some Kind.TYPE entries")
    sigs.foreach { case (n, sig) => assert(sig.nonEmpty, s"empty signature for type $n") }
