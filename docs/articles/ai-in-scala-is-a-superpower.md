# AI in Scala is a Superpower: Beyond Basic Code Navigation

In my previous post, [*It Hurts to Watch an AI Grep My Scala*](it-hurts-to-watch-ai-grep-my-scala.md), I talked about why traditional text search, like "grep" is a pretty bad tool when LLM agents navigate Scala codebases. String pattern search simply couldn't work with relationships and guarantees of type system (especially an advanced one like in Scala). Another problem with LLMs and code - models are hard to imagine inferred types, summoned `given` instances, or renamed imports.

Once your AI assistant stops guessing with "grep" and gets direct access to the compiler's **SemanticDB**, what can it *actually* do?

Here is how **ScalaSemantic MCP** turns your AI assistant (Claude Code, Codex, Antigravity) further, into a high-precision Scala co-developer.

---

## ⚡ 1. Inline Semantic Enrichment (`annotated_source`)

LLMs, when reading your codebase, are just trying to guess about semantics going there, and sometimes they are impressively good at it, but why try to guess when you just can get? All real, guaranteed hidden information about your code. No guess need anymore.

The `annotated_source` tool enriches files read on the fly before sending them to the model's context window:
* **Explicit Type Annotations**: Automatically inserts inferred types for `val`, `var`, and `def` declarations.
* **Summoned Givens & Implicits**: Explicitly names and binds summoned `given` instances by their type, eliminating mystery parameters.
* **Exploded Wildcard Imports**: Replaces wildcard imports (`import foo.bar.*`) with the exact symbols used in the file.
* **Diff Formatting (`format=diff`)**: Emits clean diff-style representations showing exactly what the compiler sees versus what is written on disk.

Take a look at what `annotated_source` added to the code for LLMs

![Inline Semantic Annotations Diff](Screenshot%202026-07-21%20at%2003.48.01.png)

As shown in the screenshot above, it highlights the exact semantic additions—such as inferred return types, parameter types, and summoned context bounds—making the code 100% unambiguous to the LLM.

---

## 🔍 2. Deep Structural Inspection & Project Relationships

Rather than forcing the LLM to open 10 files to understand relationships, ScalaSemantic provides targeted semantic query tools that extract project structures and dependencies. 

![Project Structure and Relationships Graph](Screenshot%202026-07-21%20at%2003.48.36.png)

Above, I visualize a tool-created graph of ScalaSemanticMCP complex project relationships and module dependencies. Besides graph edges it also contains additional math measurements about this graph: for example node coupling, centrality, depth, etc. This help reasoning about a code much more, then just grepping here and there.


---
## 3. What else can it give to an LLM agent

| Tool                          | What It Does                                                                                             |
| :---------------------------- | :------------------------------------------------------------------------------------------------------- |
| **`value_flow`**              | Performs BFS graph traversal to trace where a variable or parameter originates and flows across methods. |
| **`source_around_position`**  | Pulls pinpointed, contextual source code around exact file coordinates without loading entire files.     |
| **`smart_code_duplications`** | Finds structural AST duplications across the project with highlighted source snippets.                   |
| **`method_call_hierarchy`**   | Traverses incoming callers and outgoing call chains across parent traits and overrides.                  |
| **`structure` (Mermaid)**     | Emits visual Mermaid module graphs for architecture visualization directly in chat markdown.             |

---

## 🚀 4. Zero-Config Build Tool Auto-Detection

ScalaSemantic requires **no sbt or Mill plugin installation**. It automatically detects `.semanticdb` files across **sbt**, **Mill**, and **Scala CLI** configurations out of the box.

```bash
# Run automatically via Scala CLI launcher
curl -sSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/main/scalasemantic-mcp.sh | bash
```

### Try It Today
Connect ScalaSemantic to your AI coding environment and give your assistant true compiler-native understanding of your Scala codebase.
