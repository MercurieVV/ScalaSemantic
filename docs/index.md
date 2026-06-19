# ScalaSemantic

Deep semantic analysis of Scala projects over [MCP](https://modelcontextprotocol.io). It reads
compiler-emitted **SemanticDB**, so answers reflect what the compiler resolved — not text matching.

> The Scala fences below are compiled + executed by **mdoc** at site-build time, so their output is
> real. They model the data shapes rather than call the analyzer in-process: the analyzer is built on
> a newer Scala than mdoc's snippet compiler supports (see [Development](DEVELOPMENT.md)).

## SemanticDB symbol grammar

Every tool takes a SemanticDB **symbol string**, whose terminator encodes the kind:

```scala mdoc
enum Descriptor(val terminator: String):
  case Package extends Descriptor("/") //  com/example/
  case Type    extends Descriptor("#") //  Animal#
  case Term    extends Descriptor(".") //  Sample.
  case Method  extends Descriptor(").") // render(). , render(+1). for an overload

Descriptor.values.map(d => s"${d}${d.terminator}").toList
```

So `com/example/Animal#` is the type `Animal` in package `com.example`, and
`com/example/Sample.render().` is a method.

## Talking to the server (stdio JSON-RPC)

`find_symbol` turns a plain name into a symbol; feed that symbol into the richer tools:

```scala mdoc
def toolCallJson(id: Int, tool: String, arguments: String): String =
  s"""{
     |  "jsonrpc": "2.0",
     |  "id": $id,
     |  "method": "tools/call",
     |  "params": {
     |    "name": "$tool",
     |    "arguments": $arguments
     |  }
     |}""".stripMargin

def requests: List[String] = List(
  toolCallJson(1, "find_symbol", """{"query": "Animal"}"""),
  toolCallJson(2, "class_hierarchy", """{"symbol": "com/example/Animal#"}""")
)

assert(requests.head.contains(""""name": "find_symbol""""))
assert(requests(1).contains(""""symbol": "com/example/Animal#""""))

requests.foreach(println)
```

## Pages

- [Integration](INTEGRATION.md) — register the server with an MCP client
- [FAQ](FAQ.md) — MCP, AI-agent, and SemanticDB basics for Scala developers
- [Examples](EXAMPLES.md) — sample MCP queries, responses, and grep comparisons
- [Comparison](COMPARISON.md) — vs `grep` (pros & cons), plus a note on Metals/LSP
- [Development](DEVELOPMENT.md) — modules, build, cross-version testing, this site
- [Design decisions](DESIGN.md) — upickle, extensibility, docs tooling
- [Releasing](RELEASING.md) — Sonatype Central release process
- [Release notes](RELEASE_NOTES.md) — user-facing changes per version
- [Plan](PLAN.md) — design rationale & tracker
