package com.github.mercurievv.scalasemantic.analysis

import java.nio.file.Files
import java.nio.file.Paths

/** Golden-value test for [[SourceSentinel]]. `inject`'s exact output — and the fact that `strip`
  * reverses it back to the original — are locked against committed reference files, so any drift in
  * the sentinel format (marker text, block placement) is caught explicitly rather than only by the
  * narrower unit assertions in [[SourceSentinelSuite]].
  *
  *   - **First run** (golden file absent): the file is written automatically and the test passes.
  *     Review the written file, then commit it.
  *   - **Subsequent runs**: the live output must match the committed file byte-for-byte.
  *   - **Regenerate**: delete the golden file and re-run the test twice (first run writes, second
  *     run verifies).
  */
class SourceSentinelGoldenSuite extends munit.FunSuite:

  // Deliberately includes a real `//` trailing comment and a real `/** */` doc comment, so the
  // golden proves both survive `inject` untouched and are never stripped by `strip`.
  private val fixture: Vector[String] = Vector(
    "package com.example",
    "",
    "/** A tiny fixture class. */",
    "class Greeter:",
    "  def greet(name: String): String = // real trailing comment, must survive",
    "    s\"Hello, $name\""
  )

  private val notes = List(
    SourceSentinel.Note(4, "type=String"),
    SourceSentinel.Note(5, "type=String")
  )

  private def readOrWrite(path: java.nio.file.Path, actual: String): Unit =
    if Files.exists(path) then assertEquals(actual, Files.readString(path))
    else
      val _ = Files.createDirectories(path.getParent)
      val _ = Files.writeString(path, actual)

  test("inject output matches golden reference"):
    val injected = SourceSentinel.inject(fixture, notes)
    readOrWrite(
      Paths.get("analysis/src/test/resources/golden/source_sentinel_inject.txt"),
      injected.mkString("\n") + "\n"
    )

  test("strip(inject(fixture)) round-trips to the original fixture, matching golden reference"):
    val roundTripped = SourceSentinel.strip(SourceSentinel.inject(fixture, notes))
    assertEquals(roundTripped, fixture)
    readOrWrite(
      Paths.get("analysis/src/test/resources/golden/source_sentinel_roundtrip.txt"),
      roundTripped.mkString("\n") + "\n"
    )
