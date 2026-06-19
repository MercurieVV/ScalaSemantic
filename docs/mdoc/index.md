---
id: index
title: ScalaSemantic
slug: /
---

# ScalaSemantic

Deep semantic analysis of Scala projects over [MCP](https://modelcontextprotocol.io). It reads
compiler-emitted **SemanticDB**, so answers reflect what the compiler resolved — not text matching.

> The Scala fences below are compiled + executed by **mdoc** at site-build time, so their output is
> real. They model the data shapes rather than call the analyzer in-process: the analyzer is built on
> a newer Scala than mdoc's snippet compiler supports (see
> [Development](https://github.com/MercurieVV/ScalaSemantic/blob/master/docs/DEVELOPMENT.md)).

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

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"find_symbol","arguments":{"query":"Animal"}}}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call",
 "params":{"name":"class_hierarchy","arguments":{"symbol":"com/example/Animal#"}}}
```

Full tool list and integration steps:
[README](https://github.com/MercurieVV/ScalaSemantic#tools) ·
[Integration](https://github.com/MercurieVV/ScalaSemantic/blob/master/docs/INTEGRATION.md).
