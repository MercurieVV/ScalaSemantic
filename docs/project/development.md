# Development

## Modules

Three Mill modules, one per architectural layer (package base `com.github.mercurievv.scalasemantic`):

```
mcp        stdio JSON-RPC server + entrypoint   (…​.mcp)               → dependsOn analysis
  analysis   query engine + result models       (…​.analysis, …​.model) → dependsOn core
    core       load + index SemanticDB           (…​.semanticdb)
```

`core` has no JSON or MCP dependencies; `analysis` adds upickle result models; `mcp` is the only module that speaks the protocol. One additional un-aggregated module: `compat-fixtures` (cross-compiled fixture sources for the cross-version test).

Each module emits its own SemanticDB (`def semanticDbEnabled = true`), so tests dogfood on this codebase: they load `SemanticIndex.fromProject(".")` and query the whole index.

## Build & test

```sh
./mill __.compile              # also (re)emits SemanticDB for every module
./mill __.test                 # dogfooded on core, analysis (incl. CompatSuite), mcp
./mill prePush                 # format/scalafix checks; compat goldens; tests; stainless/smoke/regression; docs site when docs changed
./mill mcpClientConfig --client all   # generate/merge configs and rules for all clients (dogfooding)
```

`scalafixAll` is not part of `prePush` — no Mill 1.x build of `mill-scalafix` exists yet (see
`docs/MILL_MIGRATION.md` §2).

Run the server from source:

```sh
./mill mcp.runMain com.github.mercurievv.scalasemantic.mcpServer <root>
```

## Cross-version compatibility test

The analyzer reads SemanticDB emitted by any Scala version, not just the one it is built with. `compat-fixtures/` holds mirror fixtures (`src/main/scala-2.13` and `src/main/scala-3`) cross-compiled to produce golden `*.semanticdb` files, committed under `analysis/src/test/resources/compat/scala-<binVersion>/`. `CompatSuite` discovers every golden dir and runs the full analyzer surface against each.

```sh
./mill compatGoldenAll   # recompile fixtures for every version in compatScalaVersions, refresh golden
```

Add a version by appending to `compatFixtures`'s cross versions in `build.mill` and rerunning `compatGoldenAll`.

## Documentation site (mdoc + Docusaurus)

```sh
./mill docs.run                              # mdoc renders docs → website/docs (executes Scala fences)
cd website && npm ci && npm run build        # Docusaurus static site (Node 18+)
```

`./mill prePush` runs the docs-site build automatically when changes touch `docs/`, `mdoc-docs/`,
`website/`, or `scripts/gen-release-notes.sh`. It expects `website/node_modules` to already exist;
run `cd website && npm ci` once before the gate if you have not installed the site dependencies.

## Build & test gotchas

- **Build tool**: Mill 1.1.7 (`build.mill`, self-downloading `./mill` script committed at repo root). `build.sbt`/`project/` are deleted; the CI `publish` job (`sbt-ci-release`) and `mutation.yml` (stryker4s) are both gated `if: false` pending a Mill 1.x port for each — don't use `sbt` at all. See `docs/MILL_MIGRATION.md` for full migration status.
- **SemanticDB bindings**: `scala.meta.internal.semanticdb.*` is in `semanticdb-shared` (not in `scalameta`).
- **Wartremover**: pinned to 3.6.0 — 3.5.6 had no artifact for Scala 3.8.4; no Mill plugin exists, so it's wired by hand as a compiler-plugin dep + `-P:wartremover:…` scalacOptions per module.

More decisions and history: [Design decisions](design.md).
