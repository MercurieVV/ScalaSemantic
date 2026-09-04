# Source Sentinel Isomorphism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pure, round-trip-safe engine that appends synthetic technical-annotation blocks
to the end of a source line for an LLM to read, and strips exactly those blocks back out on write
— so the annotations exist only in the LLM's view, never persist in the real file, and never touch
real human-written comments. `annotated_source`/`symbol_source` are the intended consumer (same
tool becomes read+write isomorphic) — see "Explicitly out of scope" for why that wiring isn't in
this plan yet.

**Architecture:** One pure object, `SourceSentinel`, in the `analysis` module: `inject(lines,
notes)` appends one `SEM:...:SEM` block to the end of each annotated line (no line is inserted, so
line numbers stay stable and usable as a reference); `strip(lines)` removes only text matching
`SEM:...:SEM` (plus one leading space). The `:SEM` end marker delimits the block precisely, so
content already on the line before the block (a real trailing `//` comment) — or, in principle,
after it — survives untouched. `inject` then `strip` is proven to be the identity on the original
lines — that's the isomorphism.

**Tech Stack:** Scala 3.8.4, Mill, MUnit (`munit.FunSuite` for golden, `munit.ScalaCheckSuite` +
`org.scalacheck` for property tests) — matches `analysis/src/test/scala/.../analysis/`.

**Spec:** This conversation's design (no separate spec doc). Governing decisions, in the order
they were settled:
1. One tool does both read and write — `annotated_source`'s existing rendering stays as-is;
   sentinel wrapping/stripping is added to (not split off from) that same tool. No separate
   `annotated_write` tool.
2. The annotation is appended to the end of the line it describes, not inserted as its own line —
   so line numbers never shift and can be used as a stable reference.
3. The block is delimited by an explicit start (`SEM:`) and end (`:SEM`) marker, not just "rest of
   line", so real content can follow the block on the same line without being eaten by `strip`.

## Global Constraints

- The sentinel block is `"SEM:" + payload + ":SEM"`, appended after exactly one space to the end
  of the target line. `SourceSentinel.Start = "SEM:"`, `SourceSentinel.End = ":SEM"` — build
  strings from these constants, never re-type the literal markers elsewhere.
- Plain ASCII markers (not a Private-Use-Area codepoint): readable in any editor/diff, at the cost
  of a residual collision risk if a real comment happens to contain literal `SEM:...:SEM` text —
  accepted tradeoff per the spec decision above, not revisited in this plan.
- `inject`/`strip` are pure functions over `IndexedSeq[String]` (lines) — no file I/O, no MCP
  wiring, in this plan. Wiring this into `annotated_source`/`symbol_source`'s actual rendering AND
  giving that tool a write path is explicitly **out of scope** here — see below.
- Golden files are the source of truth per task 2: once committed, any drift fails the test; only
  a deliberate `rm` + rerun regenerates them, and the new content must be verified before commit.
- Wartremover: no `var`, no `.asInstanceOf`, no `null`, no `throw` — pure `map`/`flatMap`/`filter`/
  regex only.

---

### Task 1: `SourceSentinel` core — `inject` / `strip`

**Files:**
- Create: `analysis/src/main/scala/com/github/mercurievv/scalasemantic/analysis/SourceSentinel.scala`
- Test: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/SourceSentinelSuite.scala`

**Interfaces:**
- Produces (used by Task 2 and Task 3):
  - `object SourceSentinel`
  - `SourceSentinel.Start: String = "SEM:"`, `SourceSentinel.End: String = ":SEM"`
  - `final case class SourceSentinel.Note(line: Int, payload: String)` — `line` is the 0-based
    index into `lines` that the note attaches to; `payload` is arbitrary text that must not
    itself contain `\n` or the literal substring `:SEM`.
  - `def inject(lines: IndexedSeq[String], notes: List[Note]): IndexedSeq[String]` — same line
    count as `lines`.
  - `def strip(lines: IndexedSeq[String]): IndexedSeq[String]`

- [x] **Step 1: Write the failing tests**

```scala
package com.github.mercurievv.scalasemantic.analysis

class SourceSentinelSuite extends munit.FunSuite:

  test("inject appends a SEM:...:SEM block to the end of the noted line, line count unchanged"):
    val lines = Vector("object Foo:", "  def bar = 1")
    val out = SourceSentinel.inject(lines, List(SourceSentinel.Note(1, "type=Int")))
    assertEquals(out, Vector("object Foo:", "  def bar = 1 SEM:type=Int:SEM"))

  test("inject with no notes returns lines unchanged"):
    val lines = Vector("val x = 1", "val y = 2")
    assertEquals(SourceSentinel.inject(lines, Nil), lines)

  test("inject with two notes on the same line joins them in one block, in note order"):
    val lines = Vector("val x = 1")
    val out = SourceSentinel.inject(
      lines,
      List(SourceSentinel.Note(0, "a"), SourceSentinel.Note(0, "b"))
    )
    assertEquals(out, Vector("val x = 1 SEM:a; b:SEM"))

  test("inject preserves a real trailing comment already on the noted line, block goes after it"):
    val lines = Vector("  def bar = 1 // real trailing comment")
    val out = SourceSentinel.inject(lines, List(SourceSentinel.Note(0, "type=Int")))
    assertEquals(out, Vector("  def bar = 1 // real trailing comment SEM:type=Int:SEM"))

  test("strip removes only the SEM:...:SEM block, leaving everything else on the line untouched"):
    val lines = Vector(
      "  def bar = 1 // a real trailing comment SEM:type=Int:SEM",
      "  /** a real doc comment */"
    )
    assertEquals(
      SourceSentinel.strip(lines),
      Vector("  def bar = 1 // a real trailing comment", "  /** a real doc comment */")
    )

  test("strip is a no-op when there is no SEM:...:SEM block"):
    val lines = Vector("// TODO: real comment", "val x = 1")
    assertEquals(SourceSentinel.strip(lines), lines)

  test("strip leaves real text that follows the end marker on the same line"):
    val lines = Vector("val x = 1 SEM:type=Int:SEM // trailing note added after the block")
    assertEquals(
      SourceSentinel.strip(lines),
      Vector("val x = 1 // trailing note added after the block")
    )

  test("inject then strip is the identity on the original lines"):
    val lines = Vector("object Foo:", "  def bar = 1", "  val z = 2")
    val notes = List(SourceSentinel.Note(1, "type=Int"), SourceSentinel.Note(2, "type=Int"))
    assertEquals(SourceSentinel.strip(SourceSentinel.inject(lines, notes)), lines)
```

**Step 2: Run test to verify it fails** — `SourceSentinel` did not exist yet: FAIL to compile.
Confirmed.

- [x] **Step 3: Write the implementation**

```scala
package com.github.mercurievv.scalasemantic.analysis

/** A reversible annotation layer for source text shown to an LLM. `inject` appends one
  * `SEM:...:SEM` block to the end of each annotated line — no line is inserted, so line numbers
  * stay stable and can be used as a reference. `strip` removes exactly that block back out, so a
  * round trip through inject-then-strip reproduces the original text. The block is delimited on
  * both ends, so real content already on the line (a trailing `//` comment before it, or anything
  * placed after `:SEM`) is left untouched.
  */
object SourceSentinel:

  /** Opens a sentinel block. */
  val Start: String = "SEM:"

  /** Closes a sentinel block. */
  val End: String = ":SEM"

  private val BlockPattern = raw" ?SEM:.*?:SEM".r

  /** One annotation to attach to `line` (0-based index into the lines passed to `inject`). */
  final case class Note(line: Int, payload: String)

  /** Append one `SEM:...:SEM` block to the end of each noted line, joining multiple notes on the
    * same line with `"; "` inside a single block, in the order given. Lines with no note are
    * returned unchanged. The result has the same line count as `lines`.
    */
  def inject(lines: IndexedSeq[String], notes: List[Note]): IndexedSeq[String] =
    val byLine: Map[Int, List[Note]] = notes.groupBy(_.line)
    lines.zipWithIndex.map { case (line, i) =>
      byLine.get(i) match
        case None     => line
        case Some(ns) => s"$line $Start${ns.map(_.payload).mkString("; ")}$End"
    }

  /** Remove every `SEM:...:SEM` block (and the one space before it, if present) from every line.
    * Real content elsewhere on the line — including anything before the block or after it —
    * passes through unchanged.
    */
  def strip(lines: IndexedSeq[String]): IndexedSeq[String] =
    lines.map(BlockPattern.replaceAllIn(_, ""))
```

**Step 4: Run tests to verify they pass** — PASS (8/8). Confirmed.

**Step 5: Commit** — done (`refactor(analysis): SourceSentinel — inline SEM:...:SEM block, no
line insertion`).

---

### Task 2: Golden suite — sentinel output is the source of truth

**Files:**
- Create: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/SourceSentinelGoldenSuite.scala`
- Create (auto-written on first run, then committed): `analysis/src/test/resources/golden/source_sentinel_inject.txt`
- Create (auto-written on first run, then committed): `analysis/src/test/resources/golden/source_sentinel_roundtrip.txt`

**Interfaces:**
- Consumes: `SourceSentinel.{Note, inject, strip}` from Task 1 (`analysis` package, no import
  needed — same package).

- [x] **Step 1: Write the golden test**

```scala
package com.github.mercurievv.scalasemantic.analysis

import java.nio.file.Files
import java.nio.file.Paths

/** Golden-value test for [[SourceSentinel]]. `inject`'s exact output — and the fact that `strip`
  * reverses it back to the original — are locked against committed reference files, so any drift
  * in the sentinel format (marker text, block placement) is caught explicitly rather than only by
  * the narrower unit assertions in [[SourceSentinelSuite]].
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
```

**Step 2–5** — done. Regenerated golden reads:

```
package com.example

/** A tiny fixture class. */
class Greeter:
  def greet(name: String): String = // real trailing comment, must survive SEM:type=String:SEM
    s"Hello, $name" SEM:type=String:SEM
```

and the round-trip golden is byte-identical to `fixture`. Committed together with Task 1's rework
(`refactor(analysis): SourceSentinel — inline SEM:...:SEM block, no line insertion`).

---

### Task 3: Property test — round trip holds for arbitrary lines and notes

**Files:**
- Create: `analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/SourceSentinelPropertySuite.scala`

**Interfaces:**
- Consumes: `SourceSentinel.{Note, inject, strip}` from Task 1.

- [x] **Step 1: Write the property test**

```scala
package com.github.mercurievv.scalasemantic.analysis

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Property-based complement to [[SourceSentinelGoldenSuite]]: the golden locks one concrete
  * example, this generalises the round-trip law (`strip(inject(lines, notes)) == lines`) over
  * arbitrary line counts and note placements/payloads.
  */
class SourceSentinelPropertySuite extends munit.ScalaCheckSuite:

  private type Note0 = SourceSentinel.Note

  // Printable, non-empty, no-newline, no-colon text — safe as both a source line and a payload
  // (a colon could accidentally form ":SEM" and confuse the golden's readability; excluded here
  // for a clean property signal, not because inject/strip forbid it).
  private val genLine: Gen[String] =
    Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.const(' '))).map(_.mkString)

  private val genLines: Gen[Vector[String]] = Gen.listOf(genLine).map(_.toVector)

  private def genNotes(maxLine: Int): Gen[List[Note0]] =
    if maxLine < 0 then Gen.const(Nil)
    else
      Gen.listOf(
        for
          line <- Gen.choose(0, maxLine)
          payload <- Gen.alphaNumStr
        yield SourceSentinel.Note(line, payload)
      )

  property("strip(inject(lines, notes)) == lines, for any lines and any valid notes"):
    forAll(genLines) { lines =>
      forAll(genNotes(lines.length - 1)) { notes =>
        SourceSentinel.strip(SourceSentinel.inject(lines, notes)) == lines
      }
    }

  property("inject never changes the line count — it appends to existing lines, never inserts"):
    forAll(genLines) { lines =>
      forAll(genNotes(lines.length - 1)) { notes =>
        SourceSentinel.inject(lines, notes).size == lines.size
      }
    }
```

**Step 2–5** — done, both properties pass (100 cases each); full `./mill analysis.test` run
confirmed no regressions beyond one pre-existing, unrelated golden drift
(`AnalyzerTypeAliasSuite`, predates this work). Committed.

---

## Done-when

All three tasks merged; `./mill analysis.test` green (module-wide, modulo the pre-existing
unrelated `AnalyzerTypeAliasSuite` drift); `SourceSentinelSuite`, `SourceSentinelGoldenSuite`, and
`SourceSentinelPropertySuite` all pass; the two golden files exist under
`analysis/src/test/resources/golden/` and have been verified. `SourceSentinel` has no dependency
on `mcp` or `Analyzer` — it is a standalone, reusable core. **Status: done.**

## Follow-up: wired into the read side (done, separate commit)

`SourceSentinel` is now wired into `annotated_source`/`symbol_source`/`source_around_position` —
all three share `McpToolsSupport.SourceView`, so one `sentinel` boolean param on `SourceView`
(default `false`, additive — every existing call/golden is unaffected) covers all three. When on,
`render`/`renderDiff` append a `SEM:...:SEM` block per note instead of a `⟹` note. Golden:
`DocsEnrichingExamplesGoldenSuite` case `annotated_source(Enrich.scala, sentinel=on)` →
`mcp/src/test/resources/docs-golden/annotated_source_enrich_sentinel.scala`. No new tool — per
feedback, the read tool became isomorphic-capable rather than splitting a write tool off it.

## Follow-up: write path (done, separate commit)

`annotated_source` (the same tool, still no separate write tool) now accepts `write` (edited full
text) + optional `baseHash` (the `sha256` every read now carries): it strips every `SEM:...:SEM`
block via `SourceSentinel.strip` and persists the clean result to `uri`. A `baseHash` mismatch
rejects the write (`error(...)`, a thrown `RuntimeException`) rather than silently clobbering a
concurrent edit; every existing read gained a `sha256` field to make that round trip possible —
regenerated the affected `annotated_source_enrich*.json` goldens accordingly (source content
itself unchanged, only the envelope gained the field). Tests: `AnnotatedSourceWriteSuite`
(strip-and-persist, real-comment survival, baseHash accept/reject, no-baseHash overwrite).

Not decided/done: whether this fully replaces the harness's own `Edit`/`Write` tools for `.scala`
files or coexists with them (still coexists today — nothing stops a caller from bypassing
`annotated_source` and editing the file directly).

## Follow-up: marker format + leak guard (done, separate commits)

Per updated golden: the sentinel block is now wrapped as its own Scala block comment,
`/*SEM:...:SEM*/`, instead of appended bare — keeps `sentinel=on` output valid Scala on every
line regardless of what else is on it (previously a line with no pre-existing `//` comment went
invalid). `SourceSentinel.{Start,End,BlockPattern}`, the mcp-side rendering, the write-mode tests,
and the docs-golden fixture were all updated to match.

`checkNoSentinelLeak` (build.mill) greps tracked `*.scala` files for `/*SEM:...:SEM*/` and fails
`prePush` if found, excluding the feature's own known test/doc mentions
(`sentinelLeakExclusions` — an explicit, locked allowlist; a new deliberate mention must be added
there, same discipline as a golden file). Verified both directions (passes clean, fails on a
planted leak).

## Explicitly out of scope (still not started)

None currently — read (sentinel rendering), write (strip + persist, concurrent-edit guard), and
the leak guard are all done. Open architectural question, unchanged: whether `annotated_source`
write mode should fully replace the harness's own `Edit`/`Write` tools for `.scala` files, or
keep coexisting with them (today: coexists, nothing enforces going through this tool).
