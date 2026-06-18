# Development

## Modules

Three sbt modules, one per architectural layer (package base
`com.github.mercurievv.scalasemantic`):

```
mcp        stdio JSON-RPC server + entrypoint   (…​.mcp)        → dependsOn analysis
  analysis   query engine + result models       (…​.analysis, …​.model) → dependsOn core
    core       load + index SemanticDB           (…​.semanticdb)
```

`core` knows nothing about JSON or MCP; `analysis` adds upickle result models; `mcp` is the only
module that speaks the protocol. A fourth, un-aggregated module `sbt-plugin` publishes the optional
sbt plugin.

Each module emits its own SemanticDB (`semanticdbEnabled := true`), so the tests dogfood on this
codebase: they load `SemanticIndex.fromProject(".")` (repo root) and query the whole index.

## Build & test

```sh
sbt compile     # also (re)emits SemanticDB for every module
sbt test        # 33 tests, dogfooded on this project (core 5, analysis 18, mcp 10)
sbt prePush     # command alias: clean; scalafmtAll; scalafixAll; Test/testOnly * (all modules)
```

Run the server from source for development:

```sh
sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <root>"
```

## Notes / gotchas (sbt 2.0)

- `Test / test` is cached `testQuick` and skips unchanged tests — `prePush` uses `testOnly *` to force
  the full suite.
- Tasks returning a `File` need `Def.uncached`; classpaths are virtual-file refs resolved via
  `fileConverter`.
- SemanticDB bindings live in `org.scalameta:semanticdb-shared` (a 2.13 artifact consumed via
  `CrossVersion.for3Use2_13`), not in `scalameta`.

More design history and decisions: [../PLAN.md](../PLAN.md).
