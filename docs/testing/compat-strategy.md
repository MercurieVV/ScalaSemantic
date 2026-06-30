# Compatibility Fixtures Refactoring Strategy

This strategy document synthesizes findings from the [Compatibility Fixtures Coverage Audit](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/docs/testing/compat-audit.md) and [Ecosystem Research](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/docs/research/compat-fixtures-sources.md) to define a clear, actionable plan for refactoring compatibility fixtures in `compat-fixtures`.

---

## 1. Feature Area Decisions (Per-Area Strategy)

For each compiler feature area and Scala version, we evaluate whether to (a) vendor an existing corpus, (b) keep/expand hand-written bundles, or (c) author custom bundles to fill gaps.

| Feature Area | Scala 2.13 Strategy | Scala 3 Strategy | Rationale & Implementation Details |
| :--- | :--- | :--- | :--- |
| **Classes, Traits, Inheritance, Generics, Overloads** | **Keep** current hand-written bundles | **Keep** current hand-written bundles | The existing bundles (`BasicClasses`, `Inheritance`, `Generics`, `Overloads`) are clean, well-understood, and effectively verify standard semantic tools across versions. |
| **Enums & ADTs** | **Vendor** from Scalameta expect tests | **Vendor** from Scala 3 compiler expect tests | Resolves Gap 1. No enums existed in prior tests. <br>• **2.13 Source**: `scalameta/scalameta` `semanticdb-integration` sealed traits.<br>• **3 Source**: `scala/scala3` `tests/semanticdb/expect/enums.scala`.<br>• **License**: BSD 3-Clause (Scalameta) and Apache 2.0 (Scala 3). Include license comments. |
| **Opaque Type Aliases** | **Vendor** (value classes) from Scalameta | **Vendor** from Scala 3 compiler expect tests | Resolves Gap 2. Opaque types are new to Scala 3, but value classes are the 2.13 equivalent.<br>• **2.13 Source**: `scalameta/scalameta` `semanticdb-integration` value classes.<br>• **3 Source**: `scala/scala3` `tests/semanticdb/expect/opaque-types.scala`. |
| **Parameterized Traits** | *N/A* (Unsupported in 2.13) | **Vendor** from Scala 3 compiler expect tests | Resolves Gap 3. <br>• **3 Source**: `scala/scala3` `tests/semanticdb/expect/trait-parameters.scala`. |
| **Extension Methods & Implicit Classes** | **Vendor** (implicit classes) from Scalameta | **Keep / Expand** current VersionSpecific | Resolves Gap 4. Implicit classes provide Scala 2 extension methods; VersionSpecific checks Scala 3 extension methods.<br>• **2.13 Source**: `scalameta/scalameta` `semanticdb-integration` (implicit class expect tests). |
| **Procedure Syntax & Package Objects** | **Vendor** from Scalameta | *N/A* (Deprecated/removed in 3) | Resolves Gap 6. Captures deprecated Scala 2 features to ensure backward-compatibility.<br>• **2.13 Source**: `scalameta/scalameta` `semanticdb-integration` procedure syntax & package objects. |
| **Polymorphic & Implicit Call Paths** | **Author New Custom** call graph cases | **Author New Custom** call graph cases | Resolves Gap 5. Existing `CallGraph` is too flat. We need custom, multi-file virtual method dispatch and implicit extension call graphs to test `call_path` depth. |

---

## 2. Target `compat-fixtures` Layout

The new layout will introduce version-segregated files for newly-vendored and custom code:

### Scala 2.13 Files
Path: `compat-fixtures/src/main/scala-2.13/com/github/mercurievv/scalasemantic/compat/`
- [BasicClasses.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-2.13/com/github/mercurievv/scalasemantic/compat/BasicClasses.scala) (Keep)
- [Inheritance.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-2.13/com/github/mercurievv/scalasemantic/compat/Inheritance.scala) (Keep)
- [Generics.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-2.13/com/github/mercurievv/scalasemantic/compat/Generics.scala) (Keep)
- [Overloads.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-2.13/com/github/mercurievv/scalasemantic/compat/Overloads.scala) (Keep)
- [Implicits.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-2.13/com/github/mercurievv/scalasemantic/compat/Implicits.scala) (Keep)
- [CallGraph.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-2.13/com/github/mercurievv/scalasemantic/compat/CallGraph.scala) (Keep / Expand with custom polymorphic and implicit call paths)
- **`VendoredFixtures.scala`** (New): Stores BSD-3 licensed Scalameta integration tests (sealed traits, value classes, implicit classes, procedure syntax, package objects). Contains BSD-3 license header.

### Scala 3 Files
Path: `compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/`
- [BasicClasses.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/BasicClasses.scala) (Keep)
- [Inheritance.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/Inheritance.scala) (Keep)
- [Generics.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/Generics.scala) (Keep)
- [Overloads.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/Overloads.scala) (Keep)
- [Implicits.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/Implicits.scala) (Keep)
- [CallGraph.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/CallGraph.scala) (Keep / Expand with custom polymorphic and implicit call paths)
- [VersionSpecific.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/VersionSpecific.scala) (Keep)
- **`VendoredFixtures.scala`** (New): Stores Apache 2.0 licensed Scala 3 expect tests (enums, opaque types, trait parameters). Contains Apache 2.0 license header.

---

## 3. Golden-File Regeneration and Testing

1. **Generation Method**: To update the golden target directories `analysis/src/test/resources/compat/scala-2.13` and `analysis/src/test/resources/compat/scala-3`, run the sbt command alias:
   ```bash
   sbt compatGoldenAll
   ```
   This will clean old golden directories, cross-compile the new `compat-fixtures` on Scala `2.13.16` and `3.3.4` with SemanticDB enabled, and copy their outputs into the `analysis` resource folder.
2. **CompatSuite Verification**:
   The [CompatSuite.scala](file:///Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP/analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/CompatSuite.scala) will be expanded in the next phase to include test assertions verifying:
   - Enum hierarchy resolutions (both Scala 3 `enum` and Scala 2.13 `sealed trait` + `case object` structures).
   - Opaque type signatures and value class signatures.
   - Parameterized trait structure (for Scala 3).
   - Implicit class resolved signatures (for Scala 2.13).
   - Virtual method dispatch call paths (asserting polymorphic call graph edge resolution).

---

## 4. Implementation Checklist for Task #156

This checklist provides a sequenced plan for the next subtask executor:

1. [ ] **Licensing**: Create headers inside the new `VendoredFixtures.scala` files for Scala 2.13 and Scala 3, preserving the BSD 3-Clause (for Scalameta code) and Apache 2.0 (for Scala 3 compiler code) licenses.
2. [ ] **Vendor Scala 2.13 Fixtures**: Extract and paste target blocks from Scalameta's expect tests into `compat-fixtures/.../scala-2.13/VendoredFixtures.scala`.
3. [ ] **Vendor Scala 3 Fixtures**: Extract and paste target blocks from Scala 3's compiler expect tests into `compat-fixtures/.../scala-3/VendoredFixtures.scala`.
4. [ ] **Custom Call Graphs**: Author custom polymorphic and implicit call path structures in `compat-fixtures/.../CallGraph.scala` (both 2.13 and 3).
5. [ ] **Regenerate Goldens**: Run `sbt compatGoldenAll` to build the fixtures and copy SemanticDB output.
6. [ ] **Expand CompatSuite**: Update `CompatSuite.scala` with assertions for the new features (enums, opaque types/value classes, polymorphic call paths).
7. [ ] **Validate**: Run `sbt test` to ensure both compiler versions pass the suite successfully.
