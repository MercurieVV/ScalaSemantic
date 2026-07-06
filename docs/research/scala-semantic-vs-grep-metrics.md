# ScalaSemantic MCP vs. Grep Response Metrics

This document evaluates the effectiveness and token efficiency of the `scala-semantic-mcp` tools against traditional `grep`/`ripgrep` analogs.

The data is collected by analyzing the JSON-RPC queries and responses recorded in `scala-semantic-mcp.log` across **337 tool calls**.

## 1. Metrics Comparison Table

Below is the comparison of the character sizes returned by the semantic server vs. standard text matching (grep) output formatted as `filename:line:content\n` (the typical output parsed by IDEs/LLMs).

| Tool Name | Params Preview | MCP Size (chars) | Grep Size (chars) | Saving Ratio |
| :--- | :--- | :---: | :---: | :---: |
| `find_symbol` | `{"query":"rangeContains","exact":true,"limit":20}` | 426 | 6,552 | **93.5%** |
| `find_symbol` | `{"query":"McpTools","exact":true,"limit":10}` | 139 | 3,742 | **96.3%** |
| `find_symbol` | `{"query":"linearize"}` | 280 | 2,728 | **89.7%** |
| `find_symbol` | `{"name":"mcpServer"}` | 5,522 | 6,115 | **9.7%** |
| `find_symbol` | `{"name":"Main"}` | 5,522 | 911 | **-506.1%** |
| `document_outline` | `{"uri":"analysis/src/main/scala/com/github/...` | 7,747 | 27,481 | **71.8%** |
| `document_outline` | `{"uri":"analysis/src/test/scala/com/github/...` | 980 | 9,930 | **90.1%** |
| `document_outline` | `{"uri":"mcp/src/main/scala/com/github/mercur...` | 7,833 | 21,547 | **63.6%** |
| `members` | `{"symbol":"com/github/mercurievv/scalasem...` | 112 | 734 | **84.7%** |
| `class_hierarchy` | `{"symbol":"com/github/mercurievv/scalasem...` | 145 | 365 | **60.3%** |
| `method_signature` | `{"symbol":"com/github/mercurievv/scalasem...` | 344 | 497 | **30.8%** |
| `method_signature` | `{"symbol":"com/github/mercurievv/scalasem...` | 481 | 394 | **-22.1%** |
| `annotated_source` | `{"uri":"analysis/src/test/scala/com/github/...` | 18,810 | 14,793 | **-27.2%** |
| `annotated_source` | `{"uri":"mcp/src/test/scala/com/github/mercur...` | 28,549 | 19,882 | **-43.6%** |
| `annotated_source` | `{"uri":"sbt-plugin/src/test/scala/com/github/...` | 13,508 | 15,126 | **10.7%** |
| `annotated_source` | `{"uri":"project/ScalaSemanticConfigMerger.s...` | 21,051 | 16,659 | **-26.4%** |
| **Total** | **337 calls** | **3,503,621** | **4,036,479** | **13.2%** |

---

## 2. Key Insights and Analysis

- **High Savings for Search & Metadata (up to 96%)**:
  For querying tools like `find_symbol`, `document_outline`, `class_hierarchy`, and `members`, `scala-semantic-mcp` is extremely token-efficient. Instead of returning hundreds of noisy matching lines from various files, it parses the query semantically and returns a precise JSON payload.
  
- **Enriched Data for Code Reading (-10% to -50% ratio)**:
  `annotated_source` calls are generally larger in size than standard plain file reads. This is expected because the tool enriches the plain source code with compiler-inferred type annotations, implicit parameters, and implicit conversions. While this uses slightly more tokens, it provides the LLM with critical semantic context that plain-text search completely misses.

- **Overall Savings**:
  Across the entire 337-call session, the semantic tools saved **13.2% of total character output** (saving over 530,000 characters of context tokens) while delivering compiler-level accuracy.

---

## 3. Grep Analogs Used

The comparison script models how a developer would construct text searches to accomplish the same goals:

1. **`find_symbol`**: Substring text search for the query term across all source files.
2. **`find_usages`**: Regex word-boundary search (`\b<name>\b`) of the symbol's simple name across the codebase.
3. **`document_outline`**: Grep for type/class/member declarations (`\b(class|trait|object|def|val|var|type)\s`) in the target file.
4. **`annotated_source`**: Plain-text read of the entire file.
5. **`method_signature`**: Search for the method definition (`\b(def|val|var)\s+<name>\b`) across all files.
6. **`class_hierarchy`**: Combined search for type declaration (`class/trait/object <name>`) and subclass extension references (`extends/with <name>`).
7. **`members`**: Find the file defining the class, then list all internal declarations (`class`, `def`, `val`, etc.) inside that file.
8. **`structure`**: Search for all package and type declarations (`class`, `trait`, `object`, `package`) across the project.

---

## 4. Re-Running the Comparison

You can run the script using `scala-cli` to re-analyze the logs:

```bash
scala-cli run scripts/compare_grep.sc
```
