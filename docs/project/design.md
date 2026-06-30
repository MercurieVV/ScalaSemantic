# Design decisions

## Architecture

Three layers, strict dependency direction:

```
mcp       — stdio JSON-RPC server, tool registry, entry point
  analysis  — Analyzer, query methods, upickle result models
    core    — SemanticIndex: loads and indexes *.semanticdb files
```

`core` has no JSON or MCP dependencies. `analysis` adds upickle models. `mcp` is the only module that speaks the protocol.

## Key choices

**SemanticDB as the data source.** The server reads compiler-emitted `*.semanticdb` files. It does not parse source ASTs or invoke the compiler at query time. This makes queries fast (indexed once at startup) and exact (compiler-resolved, not text-matched). The tradeoff: answers are only as fresh as the last `compile`.

**Presentation compiler as a second backend.** Position-local tools (`type_at_position`, `annotated_source`) can use the live presentation compiler when the caller provides the current source text. This overlays fresh in-memory SemanticDB for one buffer on top of the compiled project index. The build tool remains responsible for full compiles; the presentation compiler only serves per-file, per-request queries.

**Lean-by-default responses.** Tool results omit empty fields, compact locations to `uri:line:col`, and render signatures as one line. Callers opt into richer output with `"detailed": true`, `"include": [...]`, or `"annotationsOnly": true`. This keeps context cost low for the common case.

**stdio process model.** The MCP client spawns the server as a child process and owns the lifecycle. Stdout is JSON-RPC only — no log lines, no sbt build output. Logging, when enabled, goes to a file. This is intentional: one stray line on stdout corrupts the protocol stream.

**No Scala MCP SDK.** JSON-RPC is hand-rolled with upickle. The protocol surface is small enough that a dependency on a hypothetical MCP Scala library would add more risk than value.

**Symbol grammar is SemanticDB's.** Tool parameters use SemanticDB symbol strings (`#` for types, `.` for terms, `().` for methods) rather than dotted class names. This avoids ambiguity between overloads, avoids re-doing name resolution, and keeps the tools composable: `find_symbol` resolves a name to a symbol; other tools take that symbol directly.

**Input validation at the MCP boundary.** The `InputTypes` module validates all raw JSON strings entering the domain (`SemanticDbSymbol`, `DocumentUri`, `ScalaIdentifier`, etc.) using smart constructors returning `Either[String, T]`. Internal layers (index, analysis) operate on already-validated types, so no re-validation is needed inside query logic.

## Future: external tool plugins

Today the tool list is hard-coded in `McpTools.all(az)`. A future design to let a separate jar contribute tools without forking:

- Define a public SPI: `trait ToolProvider { def tools(az: Analyzer): List[Tool] }`.
- Discover providers at startup with `java.util.ServiceLoader[ToolProvider]`, and/or scan a plugins directory (`~/.config/scalasemantic/plugins/*.jar`).
- `Mcp.serve` concatenates built-in tools with discovered ones.

The cost is stabilizing `Tool`, `Analyzer`, and the model types as a public API (they are internal today). This stays a research note until there is concrete demand.

## Opaque Types Decision

Based on a candidate survey:
- **Boundary Validation**: The `InputTypes` module validates raw JSON strings entering the domain (e.g., `SemanticDbSymbol`, `DocumentUri`, `ScalaIdentifier`) using smart constructors returning `Either[String, T]`.
- **No Internal Wrapping**: Internal data structures (e.g., internal symbols in `SemanticIndex`, wire models in `Models.scala`, path filters, dependency graphs) remain as standard types (like `String` or `Map`). Wrapping these internal structures does not provide additional safety benefits since they are already valid by construction, and doing so would introduce significant refactoring overhead and complexity.
- **Future Distinction**: If internal symbol strings need distinguishing in the future, a dedicated `core`-layer opaque type should be introduced in a separate PR.

## Agent Steering Strategy

To steer LLM agents away from grep and toward ScalaSemantic, we use a two-pronged approach:
1. **Server-Level MCP Instructions**: The server returns custom instructions in the `instructions` field of the MCP `InitializeResult`. Compliant clients inject these instructions directly into the LLM system prompt, ensuring out-of-the-box steering without repository changes.
2. **Repository Configuration Files**: We generate `SCALA_SEMANTIC_RULES.md` along with client-specific rule file stubs (like `CLAUDE.md`, `AGENTS.md`, `.cursorrules`, etc.) via `sbt mcpClientConfig`. This handles clients that do not support MCP instructions and provides a human-readable source of truth in the repository.

