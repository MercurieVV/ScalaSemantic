package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.model.InputTypes.*
import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import upickle.default.write

import java.nio.file.Files
import java.nio.file.Paths

/** Golden-value test for [[Analyzer.findUsages]] on the `Animal` fixture trait.
  *
  * The test serialises the live result to indented JSON and compares it against a committed
  * reference file. This catches regressions where analyzer output changes silently.
  *
  *   - **First run** (golden file absent): the file is written automatically and the test passes.
  *     Review the written file, then commit it.
  *   - **Subsequent runs**: the live JSON must match the committed file byte-for-byte.
  *   - **Regenerate**: delete the golden file and re-run `sbt test`.
  *
  * The golden path is relative to the repository root (the unforked-test working directory).
  */
class AnalyzerGoldenSuite extends munit.FunSuite:

  private val az = Analyzer(SemanticIndex.fromProject("."))
  private val Animal = "com/github/mercurievv/scalasemantic/fixtures/Animal#"

  private val goldenPath = Paths.get(
    "analysis/src/test/resources/golden/find_usages_animal.json"
  )

  test("findUsages for Animal matches golden reference"):
    val sym = SemanticDbSymbol.from(Animal).fold(fail(_), identity)
    val result = az.findUsages(sym)
    val actual = write(result, indent = 2)

    if Files.exists(goldenPath) then
      val expected = Files.readString(goldenPath)
      assertEquals(actual, expected)
    else
      val _ = Files.createDirectories(goldenPath.getParent)
      val _ = Files.writeString(goldenPath, actual)
