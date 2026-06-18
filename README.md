# ScalaSemanticMCP

An MCP server that performs deep semantic analysis of Scala projects from compiler-emitted
**SemanticDB**, exposing relationship and implicit-resolution queries that go beyond cursor-based
LSP tooling. See [docs/COMPARISON.md](docs/COMPARISON.md) for the capability comparison vs Metals.

## How it works

```
MCP stdio JSON-RPC  →  Analysis engine  →  SemanticIndex
  (com.github.mercurievv.scalasemantic.mcp)    (com.github.mercurievv.scalasemantic.analysis)   (loads *.semanticdb)
```

The analyzer reads every `*.semanticdb` file under a project root and answers queries against the
whole symbol/occurrence index. The project emits its own SemanticDB (`semanticdbEnabled := true`),
so all 33 tests run against this codebase itself.

## Tools

| Tool | Purpose |
|------|---------|
| `find_usages` | references to a symbol, def/ref split, paged |
| `method_signature` | full signature incl. implicit/using parameter lists |
| `class_hierarchy` | parents, linearization, index-wide known subtypes |
| `find_overloads` | all overloads sharing a name and owner |
| `members` | declared vs inherited members (override-aware) |
| `type_at_position` | symbol + type at a 0-based position |
| `resolve_implicits` | given definitions that produce a type |
| `trace_implicit_chain` | a given's transitive implicit dependencies |
| `call_path` | shortest call path between two methods |

**Token discipline:** results are lean by default (locations as `uri:line:col`, signatures as one
line, related symbols as display names, empty fields omitted). Pass `"detailed": true` to expand
structured data; `find_usages` is paged via `limit`/`offset`.

## Build & test

```
sbt compile     # also (re)emits this project's SemanticDB
sbt test        # 33 tests, dogfooded on this project
sbt prePush     # scalafmt + scalafix + full test suite (run before pushing)
```

## Running the server

The server speaks newline-delimited JSON-RPC 2.0 on **stdout** and logs to **stderr**. Point it at
a directory that contains emitted `*.semanticdb` files (the target project must be compiled with
SemanticDB enabled):

```
runMain com.github.mercurievv.scalasemantic.mcpServer <semanticdbRoot>   # defaults to "."
```

> Note: a bare `sbt runMain` is fine for development but writes its own build logs to stdout, which
> corrupts the JSON-RPC stream. For integration as an MCP server (e.g. in Claude Code), launch the
> compiled application directly so stdout carries only protocol messages — package a runnable jar
> (e.g. add `sbt-assembly`) and invoke `java -jar scalasemanticmcp.jar <root>`, then register that
> command as the MCP server.

### Quick manual check (dev loop, stderr discarded)

```
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"class_hierarchy","arguments":{"symbol":"com/github/mercurievv/scalasemantic/fixtures/Animal#"}}}'
```

Feed those lines to the running server's stdin; expect three JSON-RPC responses on stdout.

## Project status

Phases 0–4 complete; see [PLAN.md](PLAN.md) for the execution tracker and design decisions.
