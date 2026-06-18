# Validation: ScalaSemantic vs standard tooling (Metals / LSP)

This server reads the compiler-emitted **SemanticDB** for a whole project and answers questions
*across the entire symbol/occurrence index*. Standard LSP tooling (Metals) is built around a
cursor: "go to definition", "find references", "hover here". The two overlap on the basics, but
the index-wide and relationship queries below are awkward or impossible to get from a single LSP
request — that is where this server earns its place.

Every claim is backed by a test in `src/test/scala/...` that runs against this build's own
SemanticDB (dogfooding). Method behaviour is in `AnalyzerSuite`; the MCP wire contract is in
`McpSuite`.

## Capability comparison

| Tool | Standard LSP / Metals | This server | Evidence |
|------|-----------------------|-------------|----------|
| `find_usages` | `textDocument/references` — yes | def/ref split, paged, compact `uri:line:col` | `findUsages reports the definition and cross-type references` |
| `method_signature` | hover shows a rendered signature | explicit implicit/using lists + type params, structured on demand | `methodSignature captures type params and an implicit/using parameter list` |
| `class_hierarchy` | type hierarchy (parents/children) — yes | parents + transitive linearization + **index-wide known subtypes** in one call | `classHierarchy finds known subtypes across the index` |
| `find_overloads` | only via completion/hover at a call site | all overloads of a name+owner, anywhere | `findOverloads groups all methods sharing an owner and name` |
| `members` | document symbols (declared only) | **declared vs inherited**, override-aware, with declaring type | `members separates locally declared from inherited` |
| `type_at_position` | hover — yes | most-specific symbol + type at a position | `typeAtPosition resolves the symbol at a definition's own location` |
| `resolve_implicits` | none (no "which givens produce T?") | candidate given **definitions** producing a type | `resolveImplicits lists given definitions producing a type` |
| `trace_implicit_chain` | none | given → its implicit **dependencies**, transitively | `traceImplicitChain records implicit dependencies of each given` |
| `call_path` | call hierarchy (one hop in/out) | **shortest path between two methods** with edges | `callPath finds a transitive call chain with its edges` |

## Where it clearly beats a cursor-based LSP

1. **Known subtypes without a cursor.** Metals' type hierarchy answers "subtypes of the class at
   the cursor". This server answers "every type in the index that extends `pkg/Animal#`" as data —
   `classHierarchy` scans all `ClassSignature.parents`. Test result: `Animal → [Dog, Fish]`.

2. **Implicit/given resolution as a query.** LSP has no request for "which givens can produce
   `Show[Int]`?". `resolve_implicits` finds the given *definitions* (filtering out implicit
   parameters and the synthetic self-class a `given … with` emits) and returns
   `Show[Int]` (intShow) and `Show[List[A]]` (listShow). `trace_implicit_chain` then reports that
   `listShow` *depends on* `Show[A]` — the implicit dependency graph, which no LSP request exposes.

3. **Paths between methods.** Call hierarchy gives one hop. `call_path` runs BFS over call edges
   (attributed by source order within each document) and returns the whole chain `a → b → c` plus
   the call-site edges that realize it.

4. **Implicit parameters made explicit in signatures.** A hover renders a signature, but
   `method_signature` separates each parameter list and flags `implicit`/`using`, e.g.
   `def render[A](a: A)(implicit sh: Show[A]): String` with `parameterLists[1].implicit == true`.

## Honest limitations

- **Approximations.** `call_path` attributes a call to the nearest preceding method definition in
  source order — correct for flat method bodies, weaker for deeply nested local defs. `linearize`
  is a depth-first parent walk, not the exact Scala 3 linearization algorithm.
- **Type rendering** is best-effort Scala-ish text over SemanticDB `Type`s; exotic types fall back
  to empty/partial output rather than a precise pretty-print.
- **Index freshness.** Results are only as current as the last `compile` that emitted SemanticDB;
  the server loads the index once at startup. Metals maintains a live presentation compiler.
- **Resolution is candidate-level, not call-site-level.** `resolve_implicits` lists givens that
  *could* produce a type; it does not reproduce the compiler's exact selection/priority at a
  specific call site.

## Reproducing

```
sbt test        # 33 tests, all dogfooded on this project's own SemanticDB
sbt prePush     # format + scalafix + full test suite
```
