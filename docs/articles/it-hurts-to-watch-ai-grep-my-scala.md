# It Hurts to Watch an AI Grep My Scala

Why I built a small tool that lets AI assistants understand Scala structure and relationships instead of reading code as plain text.

You know the feeling. You ask the AI in your editor, "Where is `doThisWell` used?" — and you watch it run grep.

Sometimes it gets a lot of matches. Most are a different `doThisWell`. A few are in comments. One is inside a string. Then it opens half a dozen files to find the real ones, spends a lot of tokens, and still misses the one call that was renamed on import.

And honestly, it annoys me a little.

## The point

Here is the thing: we already solved this. The Scala compiler knows which symbol each reference points to. It resolves names, tracks types, and writes structured semantic information into SemanticDB.

That data just sits there while the AI next to it keeps guessing with grep.

So I built ScalaSemantic: a tool for AI coding agents that lets the AI ask the compiler instead of reading the text. The interesting part is not that an AI can call another tool. It is that the expensive understanding has already happened. ScalaSemantic connects the assistant to those compiler facts, so it spends less context sorting through noisy text matches and more context on the actual change.

## Three words to know

- **SemanticDB** — the compiler's semantic database: symbols, types, references, and resolved names, written at build time.
- **Presentation compiler** — the live compiler Metals uses for hover and completions. It can also understand code you just typed but haven't built yet.
- **MCP** — a standard way to give an AI assistant extra tools. You write a tool, and the AI can call it.

ScalaSemantic connects those compiler sources to the AI as MCP tools. Ask "Who calls `Service.run`?" and get the actual callers as the compiler sees them — not places where the same letters happen to appear.

## Why text search is the wrong tool for code

grep matches characters. The compiler understands symbols. These are not the same job:

- Text search **misses** references renamed on import, re-exported, or visible only through inferred types and implicits.
- Text search **over-matches** every name that looks the same, plus comments and strings. Three different `apply` methods? grep returns all of them.

For an AI, this is worse than for a human: every noisy result fills limited context and is re-sent with each follow-up question. The noise accumulates.

I measured it on my own repo: the semantic answer was about 8× smaller than the grep answer, with zero wrong matches. Smaller, exact, and no need to open six files afterward. The full analysis is in [SemanticDB vs grep](../explanation/scala-semantic-vs-grep.md).

## What it is not

Most answers come from SemanticDB and reflect your last build. If the build is stale, the data is stale. The presentation compiler covers code you just typed but haven't built yet, but not every tool uses it. Nothing here understands comments or arbitrary plain text — for those, grep is still right, and ScalaSemantic tells the AI when to use it.

## How it works

```
LLM → MCP client → stdio JSON-RPC → ScalaSemantic → SemanticDB / live compiler → compact MCP response → LLM context
```

Most tools operate on **SemanticDB symbols** — not just a method name like `run`, but the owner and descriptor too, so two unrelated `run` methods are different values. The typical agent flow:

1. Call `find_symbol` or `type_at_position` to get the exact SemanticDB symbol.
2. Pass that symbol to `find_usages`, `method_signature`, `class_hierarchy`, `call_path`, etc.

The compiled SemanticDB path covers project-wide queries. The presentation compiler covers live-buffer position queries when the caller sends the current source text.

Responses are lean by default: `uri:line:col` locations, one-line signatures, empty fields omitted. `"detailed": true` and `"include": [...]` opt into richer output — token control is intentional.

The server runs as its own JVM process. stdout is JSON-RPC only; logs go to a file. One stray log line on stdout corrupts the protocol stream, so this boundary is strict.

## Setup for an sbt project

```scala
// project/plugins.sbt
addSbtPlugin("io.github.mercurievv" % "sbt-scalasemantic-mcp" % "x.y.z")
// build.sbt
enablePlugins(ScalaSemanticMcpPlugin)
```

```sh
sbt compile          # emits SemanticDB
sbt mcpClientConfig  # writes .mcp.json + SCALA_SEMANTIC_RULES.md
```

Supported clients: `claude` (default), `codex`, `gemini`, `antigravity`, `cline`, `roo`, `continue`, `generic-json`, `all`. Recompile when code changes; project-wide answers come from compiler output on disk.

---

ScalaSemantic is open source. If you want the AI working on your Scala code to stop guessing, give it a try — and tell me where it gets things wrong.
