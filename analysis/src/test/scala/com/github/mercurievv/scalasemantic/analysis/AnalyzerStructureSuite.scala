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

  test("layering puts foundations below dependents (core layer 0, mcp above)") {
    // core depends on nothing in-project → foundation, layer 0. mcp sits on top → strictly higher.
    assertEquals(module("core").layer, 0, s"core layer=${module("core").layer}")
    assert(
      module("mcp").layer > module("core").layer,
      s"core=${module("core").layer} mcp=${module("mcp").layer}"
    )
    // No cycles here, so no symbol should be flagged ambiguous (inCycle) while still carrying a layer.
    assert(structure.symbols.forall(s => !s.combined.inCycle), "unexpected cycle in this repo")
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
