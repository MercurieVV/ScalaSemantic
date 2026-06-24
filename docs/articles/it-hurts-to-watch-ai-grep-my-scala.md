# It Hurts to Watch an AI Grep My Scala

Why I built a small tool that lets AI assistants, like Claude, understand Scala structure and relationships instead of reading code as plain text.

You know the feeling. You ask the AI in your editor, “Where is doThisWell used?” — and you watch it run grep.

Sometimes it gets a lot of matches. Most are a different doThisWell. A few are in comments. One is inside a string. Then it opens half a dozen files to find the real ones, spends a lot of tokens, and still misses the one call that was renamed on import.

And honestly, it annoys me a little.

## The point

Here is the thing: we, the Scala community, already solved this. The Scala compiler knows which symbol each reference points to. It resolves names, tracks types, and writes structured semantic information into SemanticDB.

That data just sits there while the AI next to it keeps guessing with grep.

So I built ScalaSemantic: a small tool for AI coding agents that lets the AI ask the compiler instead of reading the text.

The interesting part is not that an AI can call another tool. It is that the expensive understanding has already happened. ScalaSemantic just connects the assistant to those compiler facts, so it can spend less context sorting through noisy text matches and more context on the actual change.

## A short glossary

Three words:

- SemanticDB — the compiler’s semantic database about your code: symbols, types, references, and resolved names. It is written at build time.
- Presentation compiler — the live compiler, the one Metals uses for hover, completions, and go-to-definition. It can also understand code you just typed but have not built yet.
- MCP — a standard way to give an AI assistant extra tools. You write a tool, and the AI can call it.

ScalaSemantic connects those compiler sources to the AI as MCP tools. The AI asks, “Who calls Service.run?” and gets the actual callers as the compiler sees them — not just places where the same letters happen to appear.

## Why text search is the wrong tool for code

grep matches characters. The compiler understands symbols.

These are not the same job, and the difference is exactly the part that makes Scala Scala:

- Text search can miss references that were renamed on import, re-exported, or only become visible through inferred types and implicits. No matching text, no result.
- Text search can over-match every name that looks the same, plus comments and strings. Three different apply methods? grep returns all of them.

For a Scala engineer, this does not make much sense. For an AI, it is even worse: every noisy result fills its limited context and is sent again with each follow-up question. The noise accumulates.

I measured it on my own repo: the semantic answer was about 8× smaller than the grep answer, with zero wrong matches in that test. Smaller, exact, and no need to open six files afterward.

Yes, a normal person would just live with it. I built a whole application instead. Everyone copes differently.

## What it is not

It is not magic.

Most answers come from SemanticDB, so they reflect your last build. If the build is stale, the data can be stale too.

The presentation compiler can also inspect code you just typed and have not built yet, but not every tool uses it yet. And nothing here tries to understand comments or arbitrary plain text. For those, grep is still the right tool — and ScalaSemantic can tell the AI when to use it.

## How it works, technically

### Query flow

```text
LLM                              asks a Scala question
  -> MCP client                  chooses a tool and validates arguments
  -> stdio JSON-RPC              sends the tool call as protocol messages
  -> ScalaSemantic               runs the semantic query
  -> SemanticDB / live compiler  provides compiler facts
  -> filter + transform          keeps only the useful result
  -> compact MCP response        returns JSON
  -> LLM context                 receives exact symbols, locations, signatures, or relationships
```

Read the diagram left to right. After initialization, `tools/call` runs one query with JSON arguments. The result goes back as compact JSON inside the MCP response, ready to be added to the model's context.

Once the request reaches ScalaSemantic, **the answer comes from compiler facts, not source-text matches.** With `SemanticDB` enabled, scalac records the semantic view of each source file: definitions, references, symbols, signatures, inferred types, and synthetics. The relationships in that compiler data are the important part: they tell us what the code means after name resolution, type inference, and desugaring. That is why the tool starts from SemanticDB instead of an AST or grep output: the hard Scala work has already been done by the compiler.

That leads to the core lookup model: **most tools operate on SemanticDB symbols.** A symbol is not just a method name like `run`; it includes the owner and descriptor, so two unrelated `run` methods are different values. This makes the protocol a little more explicit, but it prevents the model from guessing which overloaded, imported, inherited, or renamed thing the user meant. The usual LLM flow is therefore resolve first, then query:

1. call `find_symbol` or `type_at_position` to get the exact SemanticDB symbol;
2. pass that symbol to a narrower tool such as `find_usages`, `method_signature`, `class_hierarchy`, or `call_path`.

The tools line up with that model:

- `find_symbol` resolves a human name to candidate SemanticDB symbols.
- `find_usages` returns occurrences of that exact symbol.
- `method_signature` renders the signature recorded by the compiler.
- `class_hierarchy`, `members`, and `call_path` derive relationships from the resolved symbol graph.
- `resolve_implicits`, `trace_implicit_chain`, and `annotated_source` expose compiler-inserted or inferred information that is easy to miss in the written source.

The compiled SemanticDB path covers the project-wide view. **The presentation compiler path covers code that has changed since the last compile.** When ScalaSemantic has the project's compile classpath, it can ask the presentation compiler to produce SemanticDB for the current buffer text. Position-local tools can use that regenerated document directly; tools that still need project context can overlay that document on top of the compiled project view. This split keeps whole-project queries stable while still letting local questions see unsaved or not-yet-compiled edits.

That boundary is intentional. **The build tool remains responsible for compiling.** ScalaSemantic reads compiler output, answers semantic questions, and returns compact JSON through MCP. It does not try to become a second build tool or a hidden IDE. That makes failures easier to reason about: if project-wide data is stale, recompile; if a live-buffer query needs current text, use the presentation compiler path.

The response is usually **compressed first and expanded only when useful**. By default, locations become `uri:line:col`, signatures are one rendered line, related symbols are display names, and empty fields are dropped. Some tools can then explode that view on request: `detailed` returns structured fields, `include` selects result sections, `find_usages` pages with `limit`/`offset`, and `annotated_source` can render as `annotated`, `compilable`, or `plain` with optional `annotationsOnly`. The point is token control: the LLM gets the smallest precise answer first, but can ask for the richer Scala-shaped view when it needs to edit or reason deeper.

### Initialization

MCP initialization is separate from the query itself. The client starts ScalaSemantic as a local stdio process and speaks JSON-RPC to it. `initialize` returns instructions, and `tools/list` returns tool names plus JSON Schemas. This keeps ScalaSemantic independent from any one AI client: Claude Code, Codex, Gemini CLI, Cline, Roo Code, Continue, or another MCP client can all use the same process.

The launcher exists mostly to keep the process boundary clean: an AI client needs protocol-only stdout, while `sbt run` writes build logs there, so ScalaSemantic runs as its own JVM process. Logs and diagnostics must stay away from stdout because stdout is the JSON-RPC transport. Boring process isolation matters here: one stray log line can corrupt the protocol stream.

## Minimal setup for an sbt project

For sbt, use the plugin. It enables SemanticDB, installs the launcher, warms the ScalaSemantic cache, writes the compile classpath file used by the presentation-compiler backend, and prints the config for your MCP client.

Add the plugin:

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "x.y.z")
```

Enable it:

```scala
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

Then compile and ask the plugin for client config:

```sh
sbt compile
sbt mcpClientConfig
```

By default it prints Claude-style `.mcp.json`, which you paste into your client config manually for now. Pick another client with `mcpClient`:

```scala
mcpClient := "codex"
```

Supported values: `claude` (default), `codex`, `gemini`, `cline`, `roo`, `continue`, `generic-json`.

Paste the generated config into your MCP client and restart the session. For non-sbt projects, emit SemanticDB and register ScalaSemantic with the project root.

Important note. Recompile when code changes; project-wide answers come from compiler output on disk.

---

ScalaSemantic is open source. If you want the AI working on your Scala code to stop guessing, give it a try — and tell me where it gets things wrong.
