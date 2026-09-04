# Measuring AI Agent Token Costs, Mutation Testing, and Deep Scala Semantics

How much does tool choice impact the token economy and reasoning quality of AI coding agents?

To answer this, I benchmarked traditional text-search tools (`grep`/`ripgrep`) against **ScalaSemantic MCP**, a compiler-indexed semantic server for Scala projects. I measured both **isolated response payloads** (synthetic benchmarks) and **real end-to-end agent workflows** (Claude Code live runs).

---

## 📊 1. Synthetic Payload Benchmarks (337 Tool Calls)

In a study analyzing 337 recorded navigation and outline tool calls across a Scala codebase:

| Tool Name | Query Type | Semantic Payload | Grep Payload | Payload Savings |
| :--- | :--- | :---: | :---: | :---: |
| **`find_symbol`** | Symbol Search | 426 chars | 6,552 chars | **93.5% Savings** |
| **`find_symbol`** | Interface Lookup | 139 chars | 3,742 chars | **96.3% Savings** |
| **`document_outline`** | File Structure | 980 chars | 9,930 chars | **90.1% Savings** |
| **`class_hierarchy`** | Subtype Graph | 145 chars | 365 chars | **60.3% Savings** |

 Across 337 tool calls, semantic tools eliminated over **530,000 characters** of noise payload compared to raw grep outputs.

### The `annotated_source` Trade-Off
Queries like `annotated_source` resulted in payloads **25% to 40% larger** than reading raw source files. This is intentional: ScalaSemantic enriches source code with compiler-inferred types, explicit `given` parameter bindings, and exploded wildcard imports. 

While individual file reads are slightly larger, this upfront semantic clarity prevents the AI agent from executing 5 to 10 subsequent exploratory file reads to locate missing implicit definitions.

---

## 📉 2. End-to-End Live Agent Benchmark (Claude Code)

To measure real-world impact over multi-turn sessions, I ran end-to-end task benchmarks using **Claude Code** on real repository tasks:

* **Task**: *"Find all definitions and references of the `Animal` trait in fixture sources."*
* **Baseline Arm (Grep + File Reading)**: ~266,332 context tokens consumed on average.
* **Semantic Arm (ScalaSemantic MCP)**: ~154,085 context tokens consumed on average.

> 🎯 **Result**: **42.1% overall token reduction** in live multi-turn agent sessions.

The token savings stemmed from eliminating speculative file-reading loops and preventing subagent exploratory explosion.

---

## 🛡️ 3. Engineering Quality: Property-Based Testing & Stryker4s

To ensure that compiler queries return precise, un-corrupted facts under all edge cases, ScalaSemantic is verified using advanced quality practices:

* **Property-Based Testing (MUnit + ScalaCheck)**: Custom AST generators test invariant properties across `linearize`, `method_signature`, `resolve_implicits`, `value_flow`, and code duplication detection (`smart_code_duplications`).
* **Stryker4s Mutation Testing**: Mutation quality gates in CI automatically inject code mutations to verify that analyzer boundary conditions and edge cases are thoroughly covered by tests.

---

## 🛠️ 4. Dogfooding & Modern Build Stack

ScalaSemantic is developed by **dogfooding the tool on its own codebase**:
* **Mill & Scala CLI**: Built with native **Mill** modules and Scala 3 syntax for rapid build/test cycles.
* **Cross-Build Auto-Detection**: Auto-detects `.semanticdb` files across **sbt**, **Mill**, and **Scala CLI** configurations.
* **One-Line Launcher**: Instant execution via `scalasemantic-mcp.sh` without requiring build file plugin edits.

---

## Conclusion

Combining compiler-indexed SemanticDB data with Property-Based Testing and Mutation Testing yields a high-precision, token-efficient foundation for AI coding agents in Scala.

Check out the project on GitHub: [MercurieVV/ScalaSemantic](https://github.com/MercurieVV/ScalaSemantic)
