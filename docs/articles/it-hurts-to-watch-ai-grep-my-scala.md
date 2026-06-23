# It Hurts to Watch an AI Grep My Scala

Why I built a small tool that lets AI assistants, like Claude, understand Scala structure and relationships instead of reading code as plain text.

You know the feeling. You ask the AI in your editor, “Where is doThisWell used?” — and you watch it run grep.

Sometimes it gets a lot of matches. Most are a different doThisWell. A few are in comments. One is inside a string. Then it opens half a dozen files to find the real ones, spends a lot of tokens, and still misses the one call that was renamed on import.

And honestly, it annoys me a little.

Here is the thing: we, the Scala community, already solved this. The Scala compiler knows which symbol each reference points to. It resolves names, tracks types, and writes structured semantic information into SemanticDB.

That data just sits there while the AI next to it keeps guessing with grep.

So I built ScalaSemantic: a small tool for AI coding agents that lets the AI ask the compiler instead of reading the text.

## A short glossary, for people who skipped the AI hype

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

The presentation compiler can also inspect code you just typed and have not built yet, but not every tool uses it yet. And nothing here tries to understand comments or arbitrary plain text. For those, grep is still the right tool — and the server can tell the AI when to use it.

## How it works, technically

ScalaSemantic is deliberately boring infrastructure.

It is a local MCP server. Your AI client starts it as a normal process over stdio, then sends JSON-RPC tool calls to it. There is no daemon to manage, no editor plugin protocol to reverse-engineer, and no dependency on a specific AI vendor. Claude Code, Codex, Gemini CLI, Cline, Roo Code, Continue, or any other MCP client can spawn the same process.

The main input is SemanticDB. When your Scala project compiles with SemanticDB enabled, the compiler writes `*.semanticdb` files containing resolved symbols, definitions, references, types, and synthetics. ScalaSemantic loads those files, walks the documents, and builds an in-memory index that can answer questions about the compiled program.

That index is why the tools can be more precise than text search:

- `find_symbol` maps a plain name to real SemanticDB symbols.
- `find_usages` finds references to that exact symbol, not just the same text.
- `class_hierarchy` follows inheritance relationships.
- `method_signature` renders the compiler-known signature.
- `resolve_implicits` and `trace_implicit_chain` expose given/implicit relationships.
- `call_path` uses recorded relationships to find a path between methods.
- `annotated_source` can show compiler insertions that are not obvious in the source text.

I kept the server read-only on purpose. It does not run your build, mutate files, or try to be an IDE. The build tool remains responsible for compiling. The MCP server reads the compiler output and answers semantic questions from it. That boundary matters: it makes the tool predictable, easy to launch from different AI clients, and safe to point at real projects.

There is a second path for fresher local context: if the server gets the target project's compile classpath, it can create a presentation-compiler backend. That is useful for position-local tools, because an AI client may send source text from an unsaved or not-yet-compiled buffer. SemanticDB remains the durable project-wide index; the presentation compiler is the overlay for live source.

The implementation is split around that boundary:

- `core` loads and indexes SemanticDB.
- `analysis` turns that index into semantic queries and compact result models.
- `pc` wraps the Scala presentation compiler for live source overlays.
- `mcp` exposes those queries as stdio MCP tools.
- `sbt-plugin` makes host projects emit SemanticDB and prints the MCP client configuration.

The annoying part was not the algorithm. The annoying part was the integration shape. An AI client needs clean stdout for JSON-RPC, so `sbt run` is wrong because sbt writes logs to stdout. The server must run as its own JVM. The launcher exists mostly to make that boring: it downloads or resolves the server, keeps protocol output clean, and lets the MCP client start it like any other stdio server.

## Minimal setup for an sbt project

For sbt, use the plugin. It enables SemanticDB, installs the launcher, warms the server cache, writes the compile classpath file used by the presentation-compiler backend, and prints the config for your MCP client.

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

By default it prints Claude-style `.mcp.json`. Pick another client with `mcpClient`:

```scala
mcpClient := "claude"       // default
mcpClient := "codex"        // Codex config.toml
mcpClient := "gemini"       // Gemini CLI settings JSON
mcpClient := "cline"        // Cline MCP JSON
mcpClient := "roo"          // Roo Code MCP JSON
mcpClient := "continue"     // Continue config.yaml
mcpClient := "generic-json" // standard mcpServers JSON
```

The generated config points the AI client at the launcher and passes two important arguments: the project root, where SemanticDB is loaded from, and a classpath file, which enables the presentation-compiler overlay. Paste the printed config into your client, restart the client/session, and ask a Scala question that should use semantic information.

For non-sbt projects, the same rule applies: make the build emit SemanticDB, install or download the launcher, and register it as a stdio MCP server with the project root as the first argument. The integration guide has the manual config shapes.

One practical note: recompile when the code changes. The project-wide answers come from compiler output on disk. If the AI asks a question about code you have not compiled yet, the answer can be stale unless the relevant tool can use the presentation-compiler overlay.

## The point

Here is the part I keep thinking about.

We spent years building compilers that understand our code precisely. Then we handed the code to AI assistants and let them read it with the simplest tool available.

That is a little strange.

The structured knowledge already exists. Someone just had to connect it.

So I connected it.

Now the AI can ask the compiler. It is faster, cheaper, and stops embarrassing itself in front of my code.

A small win. But an honest one.

---

ScalaSemantic is open source. If you want the AI working on your Scala code to stop guessing, give it a try — and tell me where it gets things wrong.
