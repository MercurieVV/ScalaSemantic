# ScalaSemantic vs `grep`

`grep` is the tool an agent reaches for by default. ScalaSemantic is the semantic complement — not a replacement. This page covers where each wins and the measured cost difference.

## What ScalaSemantic does better

- **Exact symbols, no false hits.** `find_usages` on `pkg/Foo#bar().` returns *that* method — not every `bar` in the repo, not a `bar` in a comment, not an unrelated overload.
- **No false negatives from naming.** Import aliases, backtick names, and shadowing all resolve to the same symbol; grep misses renamed-on-import references.
- **Relationships grep cannot express.** Subtypes across the whole index (`class_hierarchy`), which givens produce a type (`resolve_implicits`), the shortest call path between two methods (`call_path`), declared-vs-inherited members. These are graph queries over the compiled program.
- **Type-aware signatures.** `method_signature` renders type params and `implicit`/`using` parameter lists — information not in the source in a greppable form.

## What `grep` does better

- **Zero setup, instant.** No compile, no SemanticDB, no JVM server. Works on a fresh checkout.
- **Works on any text.** Comments, string literals, TODOs, build files, YAML, other languages.
- **Always current.** Matches bytes on disk right now; SemanticDB only sees what the last `compile` emitted.
- **Tolerates broken code.** Finds text in code that doesn't compile.

## Rule of thumb

| Question | Reach for |
|---|---|
| Where does this string / comment / TODO appear? | `grep` |
| Something in a config or non-Scala file | `grep` |
| Code doesn't compile yet | `grep` |
| Every caller of *this exact* method | `find_usages` |
| Who extends this trait? / which givens produce `T`? | `class_hierarchy` / `resolve_implicits` |
| Path from method `a` to method `c` | `call_path` |

## Token & context cost (measured)

Question: *"where is `SemanticIndex#displayName` used?"*

| | `find_usages` | `grep "displayName"` |
|---|---|---|
| Hits returned | **16** (1 def + 15 refs, all correct) | **87** matches |
| Right symbol | 16 / 16 | ~16 / 87 — the other ~71 are a different `displayName` |
| Output size | **1,630 bytes** | **12,645 bytes** |
| Approx tokens (÷4) | **~407** | **~3,161** |
| Ratio | 1× | **~7.8×** |

Reproduce:

```bash
git grep -n "displayName" -- '*.scala' | wc -c   # grep: 12645 bytes, 87 lines
# tool: find_symbol "displayName" → find_usages <symbol>  # 1630 bytes, 16 hits
```

The 7.8× is only the first request. Grep's bloat compounds: the 71 wrong hits require opening files to disambiguate (thousands more tokens), the large result re-enters the conversation as input tokens on every turn, and false negatives from renamed/re-exported references are silently missed. The semantic result is small, exact, and stays small across the whole conversation.

Prompt caching narrows the *price* gap but not the *context* gap — a cached token still occupies the context window, and the cache is frequently cold in agent edit loops.

More per-query measurements: [token-metrics-methodology.md](../research/token-metrics-methodology.md).

## Limitations

- **Index freshness.** Results reflect the last `compile`. Recompile to see new code; restart the MCP session to reload the index.
- **Compiled Scala only.** No comments, strings, generated-but-not-compiled code, or non-Scala files.
- **Approximations.** `call_path` attributes a call to the nearest preceding method definition in source order (fine for flat bodies, weaker for deeply nested local defs). `linearize` is a depth-first parent walk, not the exact Scala 3 linearization. Type rendering is best-effort on exotic types.
- **Candidate-level implicits.** `resolve_implicits` lists givens that *could* produce a type; it does not reproduce the compiler's exact selection priority at a specific call site.

## Metals/LSP comparison

Metals is **cursor-based** (go-to-def, find-refs, hover, completions) with a live presentation compiler. ScalaSemantic is **index-wide and headless** — it answers questions about the whole program over MCP, with no editor or cursor. Key additions over a single LSP request: index-wide known subtypes, implicit/given resolution as a query, the implicit dependency graph, and shortest call paths. Metals stays ahead on live freshness and editor integration.

## Reproducing

All capabilities are backed by dogfooded tests against this repo's own SemanticDB (`AnalyzerSuite`, `McpSuite`, `CompatSuite`):

```sh
sbt test
```
