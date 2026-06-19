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

## Documentation tooling: mdoc microsite

The docs are an [mdoc](https://scalameta.org/mdoc/) + [Docusaurus](https://docusaurus.io/) microsite:
Scala fences in `docs/mdoc/*.md` are compiled and *run* at build time, so their output is real and
cannot silently rot. Build it:

```sh
sbt docs/run              # mdoc renders docs/mdoc -> website/docs (executes the snippets)
cd website && npm install && npm run build   # Docusaurus static site (needs Node 18+)
```

Two constraints shaped the wiring (`docs` module in `build.sbt`, driver in
`mdoc-docs/.../DocsMain.scala`):

- **No sbt-mdoc plugin on sbt 2.0.** The published `sbt-mdoc` has no `sbt2_3` artifact, so we call the
  mdoc *library* through a tiny `DocsMain` (`mdoc.Main.process`) run via `sbt docs/run` instead of a
  plugin task.
- **mdoc's snippet compiler doesn't support the build's Scala version.** mdoc 2.9.0 cannot read the
  main modules' bleeding-edge TASTy, so the `docs` module is pinned to a Scala 3 LTS and kept
  standalone (no `dependsOn`). Consequently site snippets are illustrative Scala + protocol JSON
  rather than in-process analyzer calls. Switch them to live analyzer calls once mdoc supports the
  build's Scala line — `DocsMain` already hands mdoc a classpath, so it is a `dependsOn` + version
  bump away.
