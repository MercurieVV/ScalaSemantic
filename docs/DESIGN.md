# Design decisions

## Extensibility: adding tools from an external jar (research note — not yet built)

Today the tool list is hard-coded in `McpTools.all(az)`. A future design to let a separate jar
contribute tools without forking:

- Define a small public SPI, e.g. `trait ToolProvider { def tools(az: Analyzer): List[Tool] }`.
- Discover providers at startup with `java.util.ServiceLoader[ToolProvider]` over the classpath, and/or
  a child classloader scanning a plugins dir (e.g. `~/.config/scalasemantic/plugins/*.jar`).
- `Mcp.serve` concatenates the built-in tools with the discovered ones.

The cost is turning `Tool`, `Analyzer`, and the `model` types into a *stable public API* (they are
internal today). That is the real commitment, so this stays a research note until there is demand.
