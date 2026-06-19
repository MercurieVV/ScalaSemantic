package com.github.mercurievv.scalasemantic.analysis

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

/** Phase 1 of the structural-metrics feature, dogfooded on this repo. The module dependency
  * direction here is known (core ← analysis ← mcp, with pc beside analysis), so the computed
  * coupling must reflect it: core is stable/foundational, mcp is unstable/top, and the modules form
  * no cycle.
  */
class AnalyzerStructureSuite extends munit.FunSuite:

  private val az = Analyzer(SemanticIndex.fromProject("."))
  private val structure = az.structure()
  private def module(m: String) =
    structure.modules
      .find(_.module == m)
      .getOrElse(fail(s"no module $m; got ${structure.modules.map(_.module)}"))

  test("structure covers the project's modules and types") {
    assert(structure.symbols.nonEmpty, "expected some in-project type nodes")
    val mods = structure.modules.map(_.module).toSet
    assert(Set("core", "analysis", "mcp").subsetOf(mods), mods.toString)
  }

  test("module instability reflects the known dependency direction (core stable, mcp unstable)") {
    // core is depended on by everything and depends on nothing in-project → low instability.
    // mcp sits at the top → high instability. (Use <= to stay robust to exact counts.)
    assert(
      module("core").instability < module("mcp").instability,
      s"core=${module("core").instability} mcp=${module("mcp").instability}"
    )
    assert(module("core").afferent > 0, "core should be depended upon")
  }

  test("the module graph has no cycle (clean layering)") {
    val cyclic = structure.modules.filter(_.inCycle).map(_.module)
    assert(cyclic.isEmpty, s"modules in a cycle: $cyclic")
  }

  test("per-symbol metrics carry all four dimensions plus the combined overlay") {
    val s = structure.symbols.head
    assertEquals(
      s.perDimension.keySet,
      Set("extends", "memberType", "call", "implicit"),
      s.perDimension.keySet.toString
    )
    // instability is a proper ratio
    assert(
      structure.symbols.forall(x => x.combined.instability >= 0.0 && x.combined.instability <= 1.0)
    )
  }
