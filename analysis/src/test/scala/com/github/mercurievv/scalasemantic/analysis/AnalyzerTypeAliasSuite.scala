package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.model.OutlineEntry
import com.github.mercurievv.scalasemantic.model.SymbolKind
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import upickle.default.write

import java.nio.file.Files
import java.nio.file.Paths

/** Golden test for type-alias / opaque-type signatures in `document_outline`, dogfooded against
  * this repo's own SemanticDB.
  *
  * Regenerate by deleting `analysis/src/test/resources/golden/type_alias_signatures.json` and
  * re-running the suite (see [[AnalyzerGoldenSuite]] for the same protocol).
  */
class AnalyzerTypeAliasSuite extends munit.FunSuite:

  private val index = SemanticIndex.fromProject(".")
  private val az = Analyzer(index)

  private val goldenPath = Paths.get(
    "analysis/src/test/resources/golden/type_alias_signatures.json"
  )

  /** Every `Kind.TYPE` outline entry of the file whose uri ends with `suffix`, flattened, as
    * `name -> signature`.
    */
  private def typeEntries(suffix: String): List[(String, String)] =
    val uriStr = index.documents
      .map(_.uri)
      .find(_.endsWith(suffix))
      .getOrElse(fail(s"no indexed document ending in $suffix"))
    val uri = DocumentUri.from(uriStr).fold(fail(_), identity)
    val top = az.outline(uri).getOrElse(fail(s"$uriStr not outlined"))
    def flatten(es: List[OutlineEntry]): List[OutlineEntry] =
      es.flatMap(e => e :: flatten(e.children))
    flatten(top).filter(_.kind == SymbolKind.Type).map(e => e.name -> e.signature).sorted

  private val graphMetrics = typeEntries("analysis/graph/GraphMetrics.scala")
  private val inputTypes = typeEntries("model/InputTypes.scala")

  test("plain alias renders its right-hand side"):
    assertEquals(
      graphMetrics.toMap.get("Graph"),
      Some("= Map[String, Set[String]]")
    )

  test("every opaque type in InputTypes carries a non-empty signature"):
    assert(inputTypes.nonEmpty, "InputTypes.scala has type entries")
    val empty = inputTypes.filter((_, sig) => sig.isEmpty).map(_._1)
    assertEquals(empty, Nil, "no type entry may render an empty signature")

  test("type-alias signatures match golden reference"):
    val actual = write(
      Map(
        "GraphMetrics.scala" -> graphMetrics,
        "InputTypes.scala" -> inputTypes
      ),
      indent = 2
    )
    if Files.exists(goldenPath) then assertEquals(actual, Files.readString(goldenPath))
    else
      val _ = Files.createDirectories(goldenPath.getParent)
      val _ = Files.writeString(goldenPath, actual)
