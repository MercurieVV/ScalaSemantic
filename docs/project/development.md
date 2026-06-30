# Development

## Modules

Three sbt modules, one per architectural layer (package base `com.github.mercurievv.scalasemantic`):

```
mcp        stdio JSON-RPC server + entrypoint   (…​.mcp)               → dependsOn analysis
  analysis   query engine + result models       (…​.analysis, …​.model) → dependsOn core
    core       load + index SemanticDB           (…​.semanticdb)
```

`core` has no JSON or MCP dependencies; `analysis` adds upickle result models; `mcp` is the only module that speaks the protocol. Two additional un-aggregated modules: `sbt-plugin` (the optional sbt plugin) and `compat-fixtures` (cross-compiled fixture sources for the cross-version test).

Each module emits its own SemanticDB (`semanticdbEnabled := true`), so tests dogfood on this codebase: they load `SemanticIndex.fromProject(".")` and query the whole index.

## Build & test

```sh
sbt compile           # also (re)emits SemanticDB for every module
sbt test              # dogfooded on core, analysis (incl. CompatSuite), mcp
sbt prePush           # clean; scalafmtAll; scalafixAll; testOnly * (forces full suite)
sbt "mcpClientConfig all"   # generate/merge configs and rules for all clients (dogfooding)
```

Run the server from source:

```sh
sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <root>"
```

## Cross-version compatibility test

The analyzer reads SemanticDB emitted by any Scala version, not just the one it is built with. `compat-fixtures/` holds mirror fixtures (`src/main/scala-2.13` and `src/main/scala-3`) cross-compiled to produce golden `*.semanticdb` files, committed under `analysis/src/test/resources/compat/scala-<binVersion>/`. `CompatSuite` discovers every golden dir and runs the full analyzer surface against each.

```sh
sbt compatGoldenAll   # recompile fixtures for every version in compatScalaVersions, refresh golden
```

Add a version by appending to `compatScalaVersions` in `build.sbt` and rerunning `compatGoldenAll`.

## Documentation site (mdoc + Docusaurus)

```sh
sbt docs/run                                 # mdoc renders docs → website/docs (executes Scala fences)
cd website && npm install && npm run build   # Docusaurus static site (Node 18+)
```

## sbt 2.0 gotchas

- `Test / test` is cached `testQuick` — `prePush` uses `testOnly *` to force the full suite.
- Tasks returning a `File` need `Def.uncached`; classpaths are virtual-file refs resolved via `fileConverter`.
- SemanticDB bindings live in `org.scalameta:semanticdb-shared` (a 2.13 artifact), consumed via `CrossVersion.for3Use2_13` — not in `scalameta`.

More decisions and history: [Plan & tracker](../research/plan.md) | [Design decisions](design.md).
