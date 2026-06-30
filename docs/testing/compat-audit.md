# Compatibility Fixtures Coverage Audit

This audit evaluates the current coverage of test fixtures in `compat-fixtures` (for Scala 2.13 and Scala 3) against the compiler features that our MCP semantic tools must analyze. It highlights the gaps and recommends strategies (reusing existing corpora vs. writing custom fixtures) to fill them.

## 1. Feature Coverage Matrix

This matrix maps Scala language features to the current `compat-fixtures` source files:
- **Scala 2.13 Path**: `compat-fixtures/src/main/scala-2.13/com/github/mercurievv/scalasemantic/compat/`
- **Scala 3 Path**: `compat-fixtures/src/main/scala-3/com/github/mercurievv/scalasemantic/compat/`

| Language Feature | Scala 2.13 Bundle | Scala 3 Bundle | Status / Divergence |
| :--- | :---: | :---: | :--- |
| **Classes & Traits** | `BasicClasses` | `BasicClasses` | **Present** (Identical structure: `Animal`, `Swimmer`, `Dog`, `Fish`) |
| **Inheritance** | `Inheritance` | `Inheritance` | **Present** (Identical structure: `Greeter` trait, `Robot` class) |
| **Simple Generics** | `Generics` | `Generics` | **Present** (Identical structure: parameterized `Show[A]` trait) |
| **Method Overloading** | `Overloads` | `Overloads` | **Present** (Identical structure: `over(Int)` / `over(String)`) |
| **Call Chain (Flat)** | `CallGraph` | `CallGraph` | **Present** (Identical structure: `a -> b -> c` calls) |
| **Implicit Parameters** | `Implicits` | `Implicits` | **Divergent** (2.13 uses `implicit`; 3 uses `using`) |
| **Implicit Instances** | `Implicits` | `Implicits` | **Divergent** (2.13 uses `implicit val/def`; 3 uses `given`) |
| **Type Class Derivation** | `Implicits` | `Implicits` | **Divergent** (Chained `listShow` implicit resolution) |
| **Union Types** | *Absent* | `VersionSpecific` | **Scala 3 only** (`Int | String`) |
| **Intersection Types** | *Absent* | `VersionSpecific` | **Scala 3 only** (`Animal & Swimmer`) |
| **Literal Types** | *Absent* | `VersionSpecific` | **Scala 3 only** (`42` literal type) |
| **Extension Methods** | *Absent* | `VersionSpecific` | **Scala 3 only** (`extension (s: String) def shout`) |
| **Enums** | *Absent* | *Absent* | **Gap** (No Scala 3 `enum` or Scala 2 `sealed trait` + `case object` analogues) |
| **Opaque Type Aliases** | *Absent* | *Absent* | **Gap** (No Scala 3 `opaque type` or Scala 2 `type` tag analogues) |
| **Trait Parameters** | *Absent* | *Absent* | **Gap** (No Scala 3 parameterized traits) |
| **Implicit Classes** | *Absent* | *Absent* | **Gap** (No Scala 2 implicit class extension methods) |
| **Procedure Syntax** | *Absent* | *Absent* | **Gap** (No Scala 2 procedure syntax example) |
| **Polymorphic Call Paths** | *Absent* | *Absent* | **Gap** (No interface dispatch call graphs) |
| **Implicit Call Paths** | *Absent* | *Absent* | **Gap** (No call graphs via implicit conversions/extensions) |

---

## 2. Identified Coverage Gaps & Fill Strategies

We cross-reference the gaps against the candidate corpora studied in #151 (Scalameta expects, Scala 3 tests, and Metals input) to determine the best fill strategy.

### Gap 1: Enums & ADTs
- **Impacted Tools**: `class_hierarchy`, `members`, `find_usages`.
- **Scala 3 Feature**: `enum Color { case Red, Green, Blue }`.
- **Scala 2.13 Analogue**: `sealed trait Color; case object Red extends Color; case object Green extends Color; case object Blue extends Color`.
- **Strategy**: **Reuse Existing**. 
  - *Scala 3*: Vendor from `scala/scala3` `tests/semanticdb/expect/enums.scala`.
  - *Scala 2.13*: Vendor from `scalameta/scalameta` `semanticdb-integration` (sealed traits suite).

### Gap 2: Opaque Type Aliases
- **Impacted Tools**: `method_signature`, `type_at_position`.
- **Scala 3 Feature**: `opaque type Logarithm = Double`.
- **Scala 2.13 Analogue**: Package-private type aliases or value classes (`class Logarithm(val value: Double) extends AnyVal`).
- **Strategy**: **Reuse Existing**.
  - *Scala 3*: Vendor from `scala/scala3` `tests/semanticdb/expect/opaque-types.scala`.
  - *Scala 2.13*: Vendor value classes from `scalameta/scalameta` `semanticdb-integration`.

### Gap 3: Parameterized Traits
- **Impacted Tools**: `class_hierarchy`, `method_signature`.
- **Scala 3 Feature**: `trait Friendly(val greeting: String)`.
- **Scala 2.13 Analogue**: None (traits cannot take parameters; abstract classes must be used).
- **Strategy**: **Reuse Existing** (Scala 3 only).
  - *Scala 3*: Vendor from `scala/scala3` `tests/semanticdb/expect/trait-parameters.scala`.

### Gap 4: Extension Methods (Scala 2 vs 3)
- **Impacted Tools**: `find_usages`, `call_path`, `method_signature`.
- **Scala 3 Feature**: `extension (s: String) def shout: String`. (Currently in `VersionSpecific`, but needs better integration).
- **Scala 2.13 Analogue**: `implicit class RichString(val s: String) extends AnyVal { def shout: String = ... }`.
- **Strategy**: **Reuse Existing**.
  - *Scala 2.13*: Vendor from `scalameta/scalameta` `semanticdb-integration`.
  - *Scala 3*: Keep/expand current extension tests.

### Gap 5: Polymorphic & Implicit Call Paths
- **Impacted Tools**: `call_path` (BFS path-finding).
- **Description**: The current `Calls` object is flat and sequential. It fails to test:
  1. Virtual dispatch: calling `animal.name` resulting in a call graph edge to `Dog.name`.
  2. Implicit calls: calling `"hello".shout` generating a call graph edge to the extension method `shout`.
- **Strategy**: **Needs Custom**.
  - Since `call_path` is a custom tool specific to ScalaSemantic, we need custom, targeted call graphs that combine polymorphism and implicit classes/extensions.

### Gap 6: Removed/Deprecated Syntax
- **Impacted Tools**: `document_outline`, `annotated_source`.
- **Scala 2.13 Feature**: Procedure syntax (`def foo() { ... }`) and package objects (`package object compat { ... }`).
- **Strategy**: **Reuse Existing** (Scala 2.13 only).
  - *Scala 2.13*: Vendor from `scalameta/scalameta` `semanticdb-integration` packages.
