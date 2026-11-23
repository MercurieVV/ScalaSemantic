# ADR 0006 — Moving the MCP layer from upickle to circe, with typed tool inputs

- **Status:** proposed — stage 0 is done; stages A–E need approval of the open questions below
- **Date:** 2026-11-23
- **Extends:** the typed-output work (PRs #330–#338): circe encoders + tapir `Schema` + a compact
  JSON-Schema emitter already produce every tool's `outputSchema`
- **Applies to:** `mcp` (protocol, tools, tests), `analysis` (`model/*`), `build.mill`

## Context

Today two JSON libraries live side by side:

| Where | Library | Size |
| --- | --- | --- |
| `mcp` protocol, tool bodies, `ResultShaping`, `BatchTool`, resources | `ujson.Value` as the AST | `McpTools` ~260 uses, `Mcp` ~70, plus `OutputSchema*`, `BatchTool`; ~400 more in tests |
| `mcp` typed outputs (10 tools) | circe `Encoder` + tapir `Schema`, bridged to `ujson` by a **string round-trip** in `TypedTool.encode` | one file |
| `analysis` models (`model/*`) | upickle `derives ReadWriter` | ~70 derivations in 5 files |
| tool inputs | untyped: 19 `arg*` helpers read `a.obj.get(k)`, and each tool declares its parameters as `(name, type, description)` string tuples | every tool |

Consequences we already pay for:

- the string round-trip (`circe → string → ujson`) on every typed result;
- two ways to describe a result (typed class vs hand-built `jobj`), and **no** way to describe an
  input except prose in the tool description;
- explicit `null` on the wire for the tools that write an `analysis` model (upickle writes an
  absent `Option` as `null`; the hand-built tools drop absent keys) — we had to add
  `NullableOptionals` so the schemas tell the truth;
- the stage-0 decision "keep `ujson` as the AST during migration" was explicitly temporary.

## Decision

Replace upickle/ujson in the MCP layer with circe, in five independently shippable stages. Each
stage keeps the wire output byte-identical **except stage D**, whose one deliberate wire change is
called out below.

| Stage | What | Wire change | Rough size |
| --- | --- | --- | --- |
| **A** | Replace the 19 `arg*` helpers with one `Args` view over the request JSON. Tool bodies stop touching `ujson` for input; the swap later happens in one place. | none | 1 PR |
| **B** | Typed inputs: `final case class FindSymbolArgs(...) derives Decoder, Schema` per tool; `inputSchema` is **derived** from the type (field `@description`s keep today's wording), and a decode failure produces one uniform message naming the field. Migrate by tool group, the same way outputs went. | `inputSchema` text may differ in key order/whitespace only; tests pin the parameter names, types and `required` | ~6–8 PRs |
| **C** | Flip the boundary: `Tool.run: Json => Json` (circe). Port `Mcp` (JSON-RPC envelope), `ResultShaping`, `BatchTool` (`$ref` paths), `OutputSchema*`, `TypedTool` (drops the string round-trip). Tests move to a small `J` helper (`J.obj("k" -> …)`) so ~400 call sites are mechanical. | none (see numbers, below) | 1 large mechanical PR |
| **D** | `analysis` models: upickle `ReadWriter` → circe `Encoder`/`Decoder` (`analysis` gains `circe-core`; `core` stays JSON-free). The four model-writing tools (`literal_tokens`, `contract_clauses`, `resolve_references`, `hole_context`) go through the shared encode step, which **drops `null`**. `NullableOptionals` is deleted. | **explicit `null` → key absent** in those four tools' results | 1–2 PRs |
| **E** | Remove `upickle` from `mcp` and `analysis`, the ProGuard `upickle.core.Reader/Writer` keep rules, and the doc line that says models derive `ReadWriter`. | none | 1 small PR |

Why this order: A and B shrink what C has to touch (inputs no longer go through `ujson`), C is
then a pure type swap behind a stable interface, and D is last because it is the only stage that
changes bytes and has no dependents.

### Wire-compatibility guard

Every stage is gated on the golden suites that already run real tools and compare bytes
(`DocsToolExamplesGoldenSuite`, `DocsEnrichingExamplesGoldenSuite`, the `*SchemaSuite` family),
plus one new **parity test** written before stage C: it runs every tool on fixed inputs and pins
the serialised output, so a printer or number-formatting difference fails loudly.

Known traps to handle in C, not discover there:

- **Numbers.** `ujson.Num(2.0)` prints `2`; circe prints a `Double` as `2.0`. Integral values must
  be built with `Json.fromInt`/`fromLong`. Typed outputs already are (their fields are `Int`).
- **Key order.** Both keep insertion order; `ujson.Obj.from(seq)` call sites map 1:1 to
  `Json.fromFields(seq)`.
- **Pretty printing.** Goldens use `write(…, indent = 2)`; circe's `spaces2` must be checked
  against them (separator spacing), or the printer configured to match.
- **Wartremover.** `ujson`'s `.arr`/`.obj` are mutable collections and the repo bans them; circe's
  `Json` API is immutable, which removes a recurring class of workarounds.

### Stage D: the one deliberate wire change

`literal_tokens`, `contract_clauses`, `resolve_references` and `hole_context` currently emit
`"error": null`, `"enclosing": null`, and so on. After D those keys are absent, like every other
tool. Clients that validate with the published schemas are unaffected (the schemas stop saying
`null`); clients that test `x == null` still see a missing key as null in every JSON-aware
language. It will be called out in the release notes.

## Alternatives considered

- **Keep `ujson` permanently and only add typed inputs.** Cheapest, but leaves two JSON ASTs, the
  string round-trip, and `analysis` unable to share encoders with the schema derivation.
- **Move typed outputs to upickle instead.** upickle has no tapir bridge; schema derivation is the
  reason circe won in stage 0.
- **Flip the whole layer in one PR.** A and B each remove call sites from C; doing C first would
  mean porting 19 helpers and every tool body at once, with nothing to bisect.

## Consequences

- One JSON library in `mcp`; inputs and outputs are both types, and both schemas derive from them.
- `tools/list` stops being hand-maintained parameter tuples; the 19 `arg*` helpers disappear.
- `analysis` depends on `circe-core` (a pure-Scala library already on the `mcp` classpath).
- The `McpTools.scala` bytecode budget gets easier: typed tools already shrank bodies, and decoded
  args remove the per-call parsing boilerplate.

## Open questions for the owner

1. **Stage D's `null` → absent.** Accept it, or keep explicit `null` for those four tools (then
   `NullableOptionals` stays and the encode step needs a per-tool "keep nulls" switch)?
2. **`analysis` gets `circe-core`.** Acceptable, or should `analysis` stay JSON-free and the
   encoders live in `mcp` (more code in `mcp`, models stay plain case classes)?
3. **Input validation strictness.** Today an unknown argument is silently ignored. Typed inputs
   can reject it (`additionalProperties: false`), which catches typos but breaks clients that send
   extras. Default proposed: keep ignoring, document it.
