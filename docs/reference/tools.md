# Tool reference

ScalaSemantic exposes MCP tools over stdio JSON-RPC. For most workflows, start with `find_symbol` to
turn a plain name into a SemanticDB symbol string, then pass that symbol to the more specific query.

## Tools

| Tool | Use it for |
| --- | --- |
| `find_symbol` | Resolve a plain or partial name to SemanticDB symbol strings. Start here when you do not already have the symbol. |
| `find_usages` | Find exact references to a symbol, split by definition/reference, with paging. |
| `method_signature` | Render a method signature, including type params and implicit/using parameter lists. |
| `class_hierarchy` | Inspect parents, linearization, and known subtypes across the index. |
| `find_overloads` | List overloads that share a name and owner. |
| `members` | List declared and inherited members, override-aware. |
| `type_at_position` | Return the symbol and type at a 0-based source position. |
| `resolve_implicits` | Find given definitions that can produce a requested type. |
| `trace_implicit_chain` | Follow a given's transitive implicit dependencies. |
| `call_path` | Find the shortest known call path between two methods. |

Results are lean by default: compact locations, one-line signatures, and omitted empty fields. Use
`"detailed": true` on tools that support it when the caller needs structured fields instead of compact
strings. `find_usages` is paged with `limit` and `offset`.

## SemanticDB symbol grammar

Every semantic tool takes a SemanticDB **symbol string**, whose terminator encodes the kind:

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

## Talking to the server

MCP clients call the tools for you. For a manual stdio check, a request has this JSON-RPC shape:

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

See [Examples](../usage/examples.md) for full tool-call examples and representative responses.
