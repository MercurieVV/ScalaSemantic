# Research: Reusable Scala/SemanticDB Test-Code Corpora

This research note evaluates existing purpose-built Scala test-code corpora for potential reuse as compatibility fixtures in ScalaSemantic, avoiding the need to write and maintain large sets of custom test files.

## Candidate Sources Evaluation

| Source | Path | Versions | Features Covered | SemanticDB Availability | License | Reuse Feasibility |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Scalameta** (`scalameta/scalameta`) | [semanticdb-integration](https://github.com/scalameta/scalameta/tree/main/semanticdb-integration) | Scala 2.11, 2.12, 2.13 | Basic classes, inheritance, generics, implicits, overloads, macros, structural types, refinements. | **Yes**; built specifically to generate semanticdb files and validated against text expectations. | BSD 3-Clause | **High** (recommended for Scala 2). Source files are highly focused and designed specifically to verify SemanticDB schema completeness. |
| **Scala 3 Compiler** (`scala/scala3`) | [tests/semanticdb](https://github.com/scala/scala3/tree/main/tests/semanticdb) | Scala 3.x | Scala 3 specific constructs (givens, using clauses, extension methods, union/intersection types, opaque types, enums, inline). | **Yes**; compiled with SemanticDB enabled and compared against checkfiles (`*.expect.out`). | Apache 2.0 | **High** (recommended for Scala 3). Best source for testing compiler-supported Scala 3 SemanticDB features. |
| **Scala 3 Compiler** (`scala/scala3`) | [tests/pos](https://github.com/scala/scala3/tree/main/tests/pos) | Scala 3.x | All compiler-supported features. | **No** (must compile with `-Ysemanticdb` option). | Apache 2.0 | **Medium**. Extremely large suite; best used selectively for edge cases rather than full vendoring. |
| **Scala 2 Compiler** (`scala/scala`) | [test/files/pos](https://github.com/scala/scala/tree/main/test/files/pos) | Scala 2.13.x | Standard Scala 2 features. | **No** (must compile with `semanticdb-scalac` plugin). | Apache 2.0 | **Medium**. High complexity; less targeted than the Scalameta integration tests. |
| **Metals** (`scalameta/metals`) | [tests/input](https://github.com/scalameta/metals/tree/main/tests/input/src/main/scala) | Scala 2.12, 2.13, 3.x | Editor actions (go-to-definition, reference lookups, completions, rename targets, type annotations). | **Yes** (generated as part of the metals test-harness compilation). | Apache 2.0 | **Very High**. Designed to test developer tools, matching ScalaSemantic's exact target use cases. |

---

## Detailed Findings

### 1. Scalameta SemanticDB Expect Test Corpus
- **Files/Structure**: Located in `semanticdb-integration/src/main/scala/` and checked against expectation outputs.
- **Coverage**: Extremely deep validation of the SemanticDB specification. Covers edge cases of symbol occurrences, definitions, scope boundaries, and implicit parameters/conversions.
- **Feasibility**: Excellent. We can vendor a subset of these files directly into our `compat-fixtures` project. The BSD-3 license allows redistribution with standard attribution.

### 2. Scala 3 Compiler SemanticDB Tests
- **Files/Structure**: Located in `tests/semanticdb/expect/` (often checked by compiler developers to verify that `-Ysemanticdb` outputs correct metadata).
- **Coverage**: Best-in-class coverage of advanced Scala 3 types (e.g. given instances, extension methods, union and intersection types, pattern matching on new constructs).
- **Feasibility**: Crucial for testing Scala 3 compatibility. We can vendor selected test cases from this directory directly to form our Scala 3 compatibility fixtures.

### 3. Metals Test Input Projects
- **Files/Structure**: Located in `tests/input/src/main/scala/` (in the `scalameta/metals` repository).
- **Coverage**: Contains realistic code files containing deliberate patterns (such as deep inheritance hierarchies, heavily overloaded method calls, and complex implicit chains) specifically designed to test LSP tool actions.
- **Feasibility**: Highest alignment with ScalaSemantic's tools (e.g., `find_usages`, `class_hierarchy`, `trace_implicit_chain`).

---

## Strategic Recommendation

For the implementation of `compat-fixtures` (Issue #80), we should adopt a two-pronged vendoring strategy:
1. **Scala 2.13**: Vendor the `semanticdb-integration` sources from `scalameta/scalameta` to cover basic constructs, generics, overloads, and implicits.
2. **Scala 3**: Vendor the `tests/semanticdb/expect` sources from `scala/scala3` to validate Scala 3 specific constructs (givens, using clauses, enums).
