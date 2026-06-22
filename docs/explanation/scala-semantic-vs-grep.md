# ScalaSemantic vs `grep` (and a note on Metals/LSP)

`grep` (ripgrep, IDE text search) is the tool an agent reaches for by default. ScalaSemantic isn't a
replacement for it — it's the semantic complement. This page is the honest trade-off: where each wins.

## What ScalaSemantic does better

- **Exact symbols, no false hits.** `find_usages` on `pkg/Foo#bar().` returns *that* method — not
  every `bar` in the repo, not a `bar` in a comment, not an unrelated overload. grep can't tell them
  apart.
- **No false negatives from naming.** Import aliases, backtick-escaped names, and shadowing all resolve
  to the same symbol; grep misses renamed-on-import references and over-matches common names.
- **Relationships grep simply can't express.** Subtypes across the whole index (`class_hierarchy`),
  which givens produce a type (`resolve_implicits`) and their transitive deps (`trace_implicit_chain`),
  the shortest call path between two methods (`call_path`), declared-vs-inherited members. These are
  graph queries over the compiled program, not text patterns.
- **Type-aware signatures.** `method_signature` renders type params and flags `implicit`/`using`
  parameter lists — information that isn't in the source text in a greppable form.

## What `grep` does better

- **Zero setup, instant.** No compile, no SemanticDB, no JVM server. Works on a fresh checkout.
- **Works on *any* text.** Comments, string literals, log messages, TODOs, build files, YAML, other
  languages — anything ScalaSemantic can't see because it only knows compiled Scala symbols.
- **Always current.** Matches the bytes on disk right now; never stale. ScalaSemantic only sees what
  the last `compile` emitted.
- **Tolerates broken code.** Finds text in code that doesn't compile; SemanticDB needs a successful
  compile.
- **Ubiquitous and scriptable.** Every machine has it; trivial to pipe and combine.

## Rule of thumb

| Question | Reach for |
|---|---|
| "Where does this string / comment / TODO appear?" | `grep` |
| "Something in a config or non-Scala file" | `grep` |
| "The code doesn't compile yet" | `grep` |
| "Every caller of *this exact* method" | `find_usages` |
| "Who extends this trait?" / "which givens produce `T`?" | `class_hierarchy` / `resolve_implicits` |
| "Path from method `a` to method `c`" | `call_path` |

The server's `initialize` instructions tell the agent to prefer the semantic tools for the second
group and fall back to text search for the first.

## Token & context cost (measured)

For an agent, the cost that matters is *tokens* — both the dollar cost of each request and the
finite context window the result occupies. The semantic tool wins on both, and the gap is large.

### One worked measurement (reproducible on this repo)

Question: *"where is `SemanticIndex#displayName` used?"*

| | `find_usages` (tool) | `grep "displayName"` |
|---|---|---|
| Hits returned | **16** — exactly this symbol (1 def + 15 refs) | **87** matches |
| Of those, the *right* symbol | 16 / 16 | ~16 / 87 — the other ~71 are a **different** `displayName` (scalameta's `SymbolInformation.displayName`, other members, unrelated call sites) |
| Output size | **1,630 bytes** | **12,645 bytes** |
| ≈ tokens (bytes ÷ 4) | **~407** | **~3,161** |
| Ratio | 1× | **≈ 7.8×** |

Reproduce:

```bash
git grep -n "displayName" -- '*.scala' | wc -c          # grep: 12645 bytes, 87 lines
# tool: find_symbol "displayName" (exact, pathFilter *SemanticIndex*) → symbol
#       find_usages <that symbol>                         # 1630 bytes, 16 exact hits
```

> **On the token numbers.** `bytes ÷ 4` is the standard rough heuristic, good enough for an
> order-of-magnitude comparison. For exact, model-specific counts use Anthropic's token-counting
> endpoint (`POST /v1/messages/count_tokens`,
> [docs](https://platform.claude.com/docs/en/build-with-claude/token-counting)) — never a non-Claude
> tokenizer like `tiktoken`, which mis-counts Claude tokens. The 7.8× ratio is robust to which
> counter you use because it's the same text class on both sides.

### The cost compounds — a tree of consequences

The headline 7.8× is only the *first* request. The agent loop makes it worse for grep:

```
grep "displayName"  → 87 matches, ~12.6 KB (~3.2K tokens) into the conversation
│
├─ ~71 matches are the WRONG symbol
│   └─ the model cannot tell which 16 are real from the text alone
│       └─ it opens several files to disambiguate  → +thousands more tokens
│
├─ the result is now conversation history
│   └─ the API is stateless: history is re-sent as INPUT tokens on EVERY later turn
│       (until server-side compaction)  → the 7.8× payload is paid again and again
│           └─ context window fills faster  → compaction triggers sooner
│               └─ earlier turns get summarised away  → lost detail → worse answers
│
└─ silently MISSES renamed-on-import / re-exported / type-inferred uses (false negatives)

find_usages(symbol)  → 16 exact uses, ~1.6 KB (~0.4K tokens)
│
├─ every entry is the real symbol  → no disambiguation file-reads
├─ small payload re-sent each turn  → context grows slowly → compaction deferred
└─ no false negatives (compiler-resolved, not text-matched)
```

### Does prompt caching erase the gap? No.

[Prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) lets a stable
history prefix be re-read at ~0.1× the input price instead of full price, so it does cut the *dollar*
cost of re-sending a bloated grep result. But it does **not** cut the *context* cost:

- A cached token still **occupies the context window**. A 7.8×-larger grep result still pushes the
  conversation toward its limit and toward compaction just as fast — caching changes the price, not
  the footprint.
- The cache is **prefix-match with a 5-minute default TTL**: any earlier edit invalidates everything
  after it, and an idle conversation loses the entry. Agent loops edit constantly, so the cache is
  frequently cold exactly when a large result was just added.

So caching narrows the *price* gap somewhat but leaves the *context-pressure* gap — the one that
degrades answer quality — fully intact. Context windows are finite (1M tokens for Opus 4.x / Sonnet
4.6, 200K for Haiku 4.5 —
[models](https://platform.claude.com/docs/en/about-claude/models/overview)); a 7.8× result spends
that budget 7.8× faster.

### Bottom line

For a symbol question, the tool returns a smaller, exact answer that needs no follow-up reads and
stays small across the whole conversation. grep returns a larger, noisier answer that triggers more
reads, re-bills its bulk every turn, and accelerates context exhaustion. The win is structural, not
incidental.

## Limitations (read before trusting an answer)

- **Index freshness.** Results reflect the last SemanticDB-emitting `compile`; the index loads once at
  startup. Recompile to see new code.
- **Compiled Scala only.** No comments, strings, generated-but-not-compiled, or non-Scala files.
- **Some approximations.** `call_path` attributes a call to the nearest preceding method definition in
  source order (fine for flat bodies, weaker for deeply nested local defs); `linearize` is a depth-first
  parent walk, not the exact Scala 3 linearization; type rendering is best-effort and can fall back to
  partial output on exotic types.
- **Candidate-level implicits.** `resolve_implicits` lists givens that *could* produce a type; it does
  not reproduce the compiler's exact selection/priority at a specific call site.

## And Metals/LSP?

Different shape, not really a competitor: Metals is **cursor-based** (go-to-def, find-refs, hover at a
position) with a live presentation compiler. ScalaSemantic is **index-wide and headless** — it answers
questions about the whole program as data, over MCP, with no editor or cursor. Key things it gives that
a single LSP request doesn't: index-wide known subtypes, implicit/given resolution as a query, the
implicit dependency graph, and shortest call paths. Metals stays ahead on live freshness and editor
integration.

## Reproducing

Every capability is backed by a test dogfooded on this repo's own SemanticDB
([`AnalyzerSuite`](https://github.com/MercurieVV/ScalaSemantic/blob/master/analysis/src/test/scala/com/github/mercurievv/scalasemantic/analysis/AnalyzerSuite.scala),
`McpSuite`, `CompatSuite`):

```
sbt test
```
