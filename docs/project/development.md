# Development

## Modules

Three sbt modules, one per architectural layer (package base `com.github.mercurievv.scalasemantic`):

```
mcp        stdio JSON-RPC server + entrypoint   (…​.mcp)               → dependsOn analysis
  analysis   query engine + result models       (…​.analysis, …​.model) → dependsOn core
    core       load + index SemanticDB           (…​.semanticdb)
```

`core` has no JSON or MCP dependencies; `analysis` adds upickle result models; `mcp` is the only module that speaks the protocol. One additional un-aggregated module: `compat-fixtures` (cross-compiled fixture sources for the cross-version test).

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

## Build & test gotchas

- **sbt 2.0.0 API**: `test` is now `InputKey` → use `(Test / test).toTask("")`; tasks returning a `File` (like classpaths resolved via `fileConverter`) need `Def.uncached`.
- **Meta-build conflict**: sbt 2.0 meta-build is Scala 3; plugins drag `_2.13` scala-collection-compat → `ConflictWarning.disable` in `project/plugins.sbt`.
- **SemanticDB bindings**: `scala.meta.internal.semanticdb.*` is in `semanticdb-shared` (not in `scalameta`). Both consumed via `CrossVersion.for3Use2_13`.
- **sbt 2.0 test caching**: `Test / test` is cached `testQuick` — `prePush` uses `(Test / testOnly).toTask(" *")` to force the full suite.
- **Wartremover**: pinned to 3.6.0 — 3.5.6 had no artifact for Scala 3.8.4.

More decisions and history: [Design decisions](design.md).

