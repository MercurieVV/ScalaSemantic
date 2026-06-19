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
module that speaks the protocol. Two more un-aggregated modules: `sbt-plugin` publishes the optional
sbt plugin, and `compat-fixtures` is a throwaway source set cross-compiled across Scala versions to
feed the cross-version test (see below).

Each module emits its own SemanticDB (`semanticdbEnabled := true`), so the tests dogfood on this
codebase: they load `SemanticIndex.fromProject(".")` (repo root) and query the whole index.

## Build & test

```sh
sbt compile     # also (re)emits SemanticDB for every module
sbt test        # dogfooded on this project (core, analysis incl. CompatSuite, mcp)
sbt prePush     # command alias: clean; scalafmtAll; scalafixAll; Test/testOnly * (all modules)
```

## Cross-version compatibility test

The analyzer reads SemanticDB emitted by *any* Scala, not just the version it is built with. To prove
that, `compat-fixtures/` holds mirror fixtures (`src/main/scala-2.13` and `src/main/scala-3`) that are
cross-compiled to produce golden `*.semanticdb`, committed under
`analysis/src/test/resources/compat/scala-<binVersion>/`. `CompatSuite` discovers every golden dir and
runs the full analyzer surface against each.

```sh
sbt compatGoldenAll   # recompile fixtures for every version in `compatScalaVersions`, refresh golden
```

Add a version by appending to `compatScalaVersions` in `build.sbt` and rerunning `compatGoldenAll` —
nothing else changes. CI runs `compatGoldenAll` then fails if the committed golden files drift, so the
fixtures stay honest as compilers bump. The supported-versions summary is in the
[README](../README.md#supported-scala-versions).

Run the server from source for development:

```sh
sbt "mcp/runMain com.github.mercurievv.scalasemantic.mcpServer <root>"
```

## Documentation site (mdoc + Docusaurus)

```sh
sbt docs/run                                 # mdoc renders docs/mdoc -> website/docs (runs snippets)
cd website && npm install && npm run build   # Docusaurus static site (Node 18+)
```

`sbt docs/run` compiles and executes the Scala fences so doc output stays real. Rationale and the
sbt-2.0 / Scala-version constraints behind the setup: [DESIGN.md](DESIGN.md#documentation-tooling-mdoc-microsite).

## Notes / gotchas (sbt 2.0)

- `Test / test` is cached `testQuick` and skips unchanged tests — `prePush` uses `testOnly *` to force
  the full suite.
- Tasks returning a `File` need `Def.uncached`; classpaths are virtual-file refs resolved via
  `fileConverter`.
- SemanticDB bindings live in `org.scalameta:semanticdb-shared` (a 2.13 artifact consumed via
  `CrossVersion.for3Use2_13`), not in `scalameta`.

More design history and decisions: [../PLAN.md](../PLAN.md).
