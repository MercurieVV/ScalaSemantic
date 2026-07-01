# Property-Based Testing Approach

## Decision

ScalaSemantic standardizes on `munit-scalacheck` for property-based tests.

Hedgehog is rejected for this repository for now. The project already has `munit-scalacheck`
wired into `analysis` test dependencies, and existing property suites use `munit.ScalaCheckSuite`.
Adding `hedgehog-munit` would create two property-testing styles without a clear benefit for the
current migration.

## Rationale

- **munit integration:** `munit-scalacheck` extends the test framework already used across the
  repository, so properties run with the same sbt test workflow and reporting as example tests.
- **Generator ergonomics:** ScalaCheck `Gen` is already used in `ModelsPropertySuite`,
  `AnalyzerHelpersPropertySuite`, `GraphMetricsPropertySuite`, and `InputTypesSuite`, giving later
  conversions local examples to copy.
- **Scala 3 support:** the current dependency set compiles and runs on the repository's Scala 3
  line, with no extra build-tool or runner integration.
- **Migration cost:** standardizing on one framework keeps later audit and rollout work focused on
  preserving assertions rather than reconciling two assertion and shrinker APIs.

## Reusable Generators

Shared generators live in `com.github.mercurievv.scalasemantic.testing.PropertyGens`.
They currently cover:

- core wire-model values such as `Position`, `Range`, `Location`, `SymbolRef`, and
  `MethodSignature`
- ordered ranges and guaranteed in-range points for pure range helper properties
- small graph shapes for graph metric properties

`PropertyDemoSuite` demonstrates the intended reuse pattern:

- model round-trip property: generated `MethodSignature` values round-trip through upickle
- pure helper property: generated points inside generated ranges satisfy `PureKernels.rangeContains`
- graph metric property: generated count pairs exercise `GraphMetrics.instability`

Later migrations should add generators to `PropertyGens` only when they are reusable across suites.
One-off generators can stay local to the suite that needs them.
