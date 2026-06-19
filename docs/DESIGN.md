# Design decisions

## Why upickle (ujson), not circe

The MCP server is a hand-rolled JSON-RPC loop, not a schema-first API. That shapes the JSON choice:

- **The server builds and reads dynamic JSON.** `Mcp` and `McpTools` assemble responses with
  `ujson.Obj`/`ujson.Arr` and parse requests with `ujson.read` — a mutable, dynamic JSON tree is
  exactly the right tool for ad-hoc protocol objects. circe's AST is immutable and more ceremonious
  for this style; you would spend effort fighting it.
- **Fewer dependencies → smaller fat jar.** The product ships as a `java -jar` fat jar. upickle/ujson
  is effectively dependency-free; circe pulls in `cats-core` plus `circe-core`/`generic`/`parser`.
  Every transitive dependency is weight in the assembled jar and another thing to keep on the SemanticDB
  classpath.
- **Lightweight compile-time derivation.** The result models in `model/Models.scala` derive
  `ReadWriter` with `derives`, no cats/shapeless machinery.

The result types still get type-safe (de)serialization via derived `ReadWriter`s; only the protocol
envelope and the deliberately lean tool output use the dynamic `ujson` tree.

## Extensibility: adding tools from an external jar (research note — not yet built)

Today the tool list is hard-coded in `McpTools.all(az)`. A future design to let a separate jar
contribute tools without forking:

- Define a small public SPI, e.g. `trait ToolProvider { def tools(az: Analyzer): List[Tool] }`.
- Discover providers at startup with `java.util.ServiceLoader[ToolProvider]` over the classpath, and/or
  a child classloader scanning a plugins dir (e.g. `~/.config/scalasemantic/plugins/*.jar`).
- `Mcp.serve` concatenates the built-in tools with the discovered ones.

The cost is turning `Tool`, `Analyzer`, and the `model` types into a *stable public API* (they are
internal today). That is the real commitment, so this stays a research note until there is demand.

## Documentation tooling: mdoc (recommended, deferred)

The repo's ethos is dogfooding — every capability is backed by a test against this repo's own
SemanticDB. Documentation should get the same guarantee. **Recommendation:** adopt
[mdoc](https://scalameta.org/mdoc/) so Scala snippets in the docs are compiled and *run* at doc-build
time; a snippet that calls `SemanticIndex.fromProject(".")` + an analyzer method prints real output
and cannot silently rot.

- Low-cost path (recommended now): `sbt-mdoc` checking Markdown kept in `docs/` — freshness guarantee,
  no site generator, GitHub still renders the `.md`. mdoc snippets that touch SemanticDB must run after
  `compile` (the index reads `target/` output), the same ordering the dogfood tests rely on.
- Deferred: a full Docusaurus microsite via mdoc. It adds a Node toolchain and a publish step; do it
  only if a hosted docs site becomes a goal.
