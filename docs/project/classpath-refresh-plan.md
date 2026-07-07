# Classpath Refresh Plan

## Goal

Live-buffer MCP tools need the target project's compile classpath so the presentation compiler can
resolve imports, dependencies, project modules, givens, extension methods, overloads, and inferred
types for current source text. The MCP server should not discover that classpath itself on startup:
build-tool discovery can be slow, may trigger network/downloads, and can block MCP tool discovery.

The build tool should own classpath freshness and materialize a stable project-local classpath file
when the build definition, module graph, Scala version, or dependency graph changes. Source-only
compiles should not be the refresh trigger: they are too frequent and do not usually change the
presentation compiler classpath.

## Design Choices

- Store generated classpath metadata under `.scala-semantic/`.
- Use build-tool-specific file names so repositories with multiple build tools do not collide:
  - `.scala-semantic/classpath-sbt.json`
  - `.scala-semantic/classpath-mill.json`
  - `.scala-semantic/classpath-scala-cli.json`
- Use `Compile / fullClasspath` for sbt and the equivalent compile classpath for other build
  tools. Live-buffer typechecking should match normal compilation, not runtime execution.
- Refresh metadata from build-tool dependency/configuration refresh points, not from MCP startup
  and not from ordinary source compilation.
- Keep MCP startup non-blocking. If no usable classpath metadata exists, the server starts in
  index-only mode.
- Keep backward compatibility with the existing flat path-separated classpath string/file.

## Metadata Format

Use JSON because a flat path-separated file cannot represent multi-module builds correctly.

```json
{
  "schemaVersion": 1,
  "buildTool": "sbt",
  "generatedAt": "2026-07-07T00:00:00Z",
  "modules": [
    {
      "id": "core",
      "baseDir": "core",
      "scalaVersion": "3.8.4",
      "configuration": "Compile",
      "classpath": [
        "core/target/scala-3.8.4/classes",
        "/Users/example/.cache/coursier/v1/https/repo1.maven.org/..."
      ]
    },
    {
      "id": "analysis",
      "baseDir": "analysis",
      "scalaVersion": "3.8.4",
      "configuration": "Compile",
      "classpath": [
        "analysis/target/scala-3.8.4/classes",
        "core/target/scala-3.8.4/classes",
        "/Users/example/.cache/coursier/v1/https/repo1.maven.org/..."
      ]
    }
  ]
}
```

Paths may be absolute or project-relative. The MCP reader resolves relative paths against the
project root.

## MCP Reader Behavior

1. Accept the existing classpath argument.
2. If the argument points to a JSON metadata file, parse it as module-aware classpath metadata.
3. If the argument points to a non-JSON file or contains path separators, keep the current flat
   classpath behavior.
4. For live-buffer tools with a `uri`, choose the module whose `baseDir` contains that source file.
5. If several modules match, choose the longest `baseDir` prefix.
6. If no module matches, fall back to a merged classpath from all modules in the metadata file.
7. If no classpath is available, run index-only and ignore `source` overlays as today.

When multiple build-tool metadata files exist, prefer the file explicitly passed by client config.
Automatic discovery can be added later, but the first implementation should avoid guessing.

## sbt Implementation

Implemented first as generated setup configuration in `scala-semantic.sbt`. A dedicated packaged
sbt plugin can still replace this later, but the shipped setup path now keeps build-tool code small.

Generated setup responsibilities:

- Enable SemanticDB as today.
- Define a task that writes `.scala-semantic/classpath-sbt.json`.
- Gather every relevant project module's `Compile / fullClasspath`.
- Include module id, base directory, Scala version, configuration, and classpath entries.
- Write atomically: write a temporary file, then move it into place.
- Skip rewriting if the content is unchanged.
- Keep the writer available as an explicit task.

Refresh integration target:

- Refresh after sbt reload/dependency resolution/build-structure changes, not after every
  `Compile / compile`.
- The writer may read `Compile / fullClasspath`, but the trigger should be tied to the point where
  sbt has refreshed dependency/configuration state.
- Avoid cycles with `update`, `fullClasspath`, and project aggregation. This likely belongs in a
  packaged sbt plugin with scripted tests rather than an ad hoc generated `.sbt` compile hook.

Potential opt-out setting for the plugin version:

```scala
scalaSemanticAutoClasspath := true
```

If disabled, the task remains available but is not attached to dependency/config refresh.

## Mill Implementation

Implemented for this repository as a root `scalaSemanticWriteClasspath` command in `build.mill`.

- Write `.scala-semantic/classpath-mill.json`.
- Use each module's compile classpath.
- Use the command after dependency/module configuration changes, or copy the compact task into a
  target Mill build.
- Write atomically and skip unchanged content.

Future work: extract this into a reusable Mill trait/plugin and investigate a low-friction way to
refresh metadata when Mill's dependency/module configuration tasks change. The target is not
`compile`; the target is a build-tool-native dependency/configuration refresh path that can reuse
Mill task caching and avoid rewriting on source-only compiles.

## Scala CLI Implementation

Scala CLI support is lower priority because module structure is less uniform.

- Write `.scala-semantic/classpath-scala-cli.json`.
- Represent the root source set as one module initially.
- Use Scala CLI's exported/printed compile classpath when available.
- Refresh when Scala CLI resolves project directives/dependencies/build options, not from MCP
  startup and not from every source compile. If Scala CLI does not expose a native persistent hook,
  use wrapper/setup integration as the first safe step.

## Setup Changes

Update setup/client config generation to pass the project-local metadata file path:

- sbt project: `.scala-semantic/classpath-sbt.json`
- Mill project: `.scala-semantic/classpath-mill.json`
- Scala CLI project: `.scala-semantic/classpath-scala-cli.json`

Implemented in both setup entrypoints:

- `scripts/scalasemantic-mcp.sh`
- `scripts/scalasemantic-mcp.scala`

Setup creates `.scala-semantic/` if needed, but the build tool owns file contents.

## Tests

- Unit tests for JSON encode/decode and module selection by `uri`.
- Unit tests for flat classpath backward compatibility.
- sbt scripted test:
  - dependency/config refresh writes `.scala-semantic/classpath-sbt.json`
  - dependency jars and project output dirs appear in the expected module classpath
  - changing dependencies updates the file
  - source-only compile does not need to rewrite the file
  - unchanged content does not rewrite the file
- Mill integration test:
  - dependency/module configuration changes update `.scala-semantic/classpath-mill.json`
  - source-only compile does not need to rewrite the file
- Scala CLI integration test:
  - directive/dependency changes update `.scala-semantic/classpath-scala-cli.json`
  - source-only compile does not need to rewrite the file
- MCP tests:
  - missing metadata starts index-only
  - empty/invalid metadata degrades without protocol failure
  - module-aware metadata enables live-buffer presentation compiler for the matching module
- End-to-end launcher smoke test:
  - create a temporary project root with a Scala source file that is not present in any disk
    SemanticDB index
  - write a classpath file containing the current test JVM classpath, or the dev launcher's
    equivalent runtime classpath, so the presentation compiler can resolve the Scala library and
    ScalaSemantic test dependencies
  - launch the server through the same process boundary users exercise:
    `scalasemantic-mcp.sh serve <temp-root> <classpath-file>` when a cached/local jar path is
    available, or the generated dev launcher from the build when testing without network
  - send JSON-RPC over stdio: `initialize`, `notifications/initialized`, then
    `tools/call type_at_position` with `uri`, `line`, `character`, and full `source`
  - assert the response resolves a symbol/type from the uncompiled buffer, proving the classpath
    file enabled the presentation-compiler backend
  - repeat with an empty or missing classpath file and assert the same `source` request returns
    `found: false`, proving the test is exercising classpath application rather than only the
    static index
  - keep the test offline and deterministic: do not let it download releases or resolve Maven
    dependencies during the test run
  - ensure stdout is parsed as JSON-RPC only and stderr may contain launcher diagnostics
  - shut down the child process by closing stdin and waiting with a timeout

## Rollout Order

1. Add metadata model and MCP reader while preserving flat classpath support. Done.
2. Add setup-generated sbt writer using `Compile / fullClasspath`. Done as an explicit task;
   automatic dependency/config refresh integration is pending.
3. Update setup to point projects at `.scala-semantic/classpath-<tool>.json`. Done.
4. Add Mill writer. Done for this repository as `scalaSemanticWriteClasspath`.
5. Add build-tool-native refresh integration for sbt dependency/config changes. Pending.
6. Add build-tool-native refresh integration for Mill dependency/config changes. Pending.
7. Add Scala CLI writer and dependency/config refresh path. Pending.
8. Document troubleshooting and migration from the old flat classpath file. Partially done.
