# Plan: Article 1 - AI in Scala is a Superpower: Beyond Basic Code Navigation

**Goal**: Drive developer adoption for ScalaSemantic MCP and position Scala as an ideal, AI-friendly language ecosystem when backed by compiler-native SemanticDB.

---

## 🎯 Target Audience & Strategy
* **Target Audience**: Scala developers, AI coding assistant users (Antigravity, Claude Code, Cursor, Windsurf), tech-curious developers.
* **Tone**: Developer-focused, practical, empowering, and visual.
* **Prerequisite Context**: Briefly references the previous article [*It Hurts to Watch an AI Grep My Scala*](it-hurts-to-watch-ai-grep-my-scala.md) without repeating the grep-vs-semantic basics.

---

## 📋 Outline & Structure

1. **Brief Nod & Transition (1-2 sentences)**
   * Reference previous article: *"In our previous post, [It Hurts to Watch an AI Grep My Scala](it-hurts-to-watch-ai-grep-my-scala.md), we established why text search falls short on Scala's type system. Once your AI assistant stops guessing with grep and gains direct access to SemanticDB, what can it actually do?"*

2. **Feature Showcase: Inline Semantic Enrichment (`annotated_source`)**
   * Explain how `annotated_source` automatically expands inferred types, names summoned `given` instances by type, explodes wildcard imports, and provides diff mode (`format=diff`).
   * **Visual Asset**: Include `Screenshot 2026-07-21 at 03.48.01.png` showing the diff preview of inline compiler annotations.

3. **Feature Showcase: Deep Structural Query Tools & Project Relationships**
   * **`value_flow`**: Tracing data origins via BFS graph traversal.
   * **`source_around_position`**: Instant targeted snippet fetching.
   * **`smart_code_duplications`**: AST-level code duplication detection with highlighted sources.
   * **`method_call_hierarchy` & `structure` (Mermaid)**: Visual call graphs and module dependency diagrams.
   * **Visual Asset**: Include `Screenshot 2026-07-21 at 03.48.36.png` demonstrating project relationships and graph visualization passed to the LLM.

4. **Engineering Quality & Reliability**
   * Golden Tests (source of truth).
   * Property-Based Testing (PBT with ScalaCheck invariants).
   * Stryker4s Mutation Testing (CI quality gates).
   * Stainless formal static verification.

5. **Zero-Config Onboarding & Call to Action**
   * Auto-detection for sbt, Mill, and Scala CLI with 1-line launcher script (`scalasemantic-mcp.sh`).
