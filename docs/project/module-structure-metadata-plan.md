# Module Structure Metadata Plan

## Goal

Build-tool integrations already write classpath metadata for presentation-compiler live buffers.
Add a separate metadata file for module topology so the MCP server can discover nested
`.scala-semantic` directories without a slow unrestricted filesystem scan.

When a build's subproject structure changes, the build tool should write a module-structure JSON
file into the parent module's `.scala-semantic/` directory. The MCP reader should start at the active
workspace root, read that structure, walk to child module `.scala-semantic` directories recursively,
and collect classpath metadata from every reachable module level.

## Confirmed Decisions

1. A child module path may point outside the active workspace root, for example to a git submodule
   checked out via `../shared`. If writing metadata for such a module fails, the build-tool task
   should skip that entry and log the error instead of failing the refresh.
2. Each `modules-<tool>.json` file describes only one level of direct children for its parent module.
   Build-tool writers should not merge their outputs together. MCP reader discovery may read and merge
   module lists from several build-tool-specific files in the same `.scala-semantic` directory.
3. Keep the current bounded visible-directory scan as a fallback. The code should keep direct
   metadata lookup, module-structure traversal, and fallback scan as separate stages rather than one
   combined filesystem walk.

## Metadata Format

Add a new file family under `.scala-semantic/`:

- `.scala-semantic/modules-sbt.json`
- `.scala-semantic/modules-mill.json`
- `.scala-semantic/modules-scala-cli.json`
- `.scala-semantic/modules.json`

Initial schema:

```json
{
  "schemaVersion": 1,
  "buildTool": "mill",
  "generatedAt": "2026-07-08T00:00:00Z",
  "parent": {
    "name": "root",
    "pathFromRoot": ".",
    "pathToOutDir": "out"
  },
  "modules": [
    {
      "name": "core",
      "path_from_root": "core",
      "path_to_out_dir": "out/core"
    },
    {
      "name": "analysis",
      "path_from_root": "analysis",
      "path_to_out_dir": "out/analysis"
    }
  ]
}
```

Field meaning:

- `name`: build-tool module name or stable display id.
- `path_from_root`: source/module directory relative to the active workspace root.
- `path_to_out_dir`: build output directory for that module, relative to the active workspace root.

The MCP reader should also accept `pathFromRoot` and `pathToOutDir` for compatibility with the
existing classpath metadata naming style, but build-tool writers should emit the snake_case fields
above.

The classpath metadata remains the source of classpath entries and source-base matching:
`classpath-*.json` still carries `modules[].baseDir` and `modules[].classpath`. The new
`modules-*.json` files only tell MCP where child module metadata may live.

## Directory Layout

For a root with nested subprojects:

```text
.
├── .scala-semantic/
│   ├── modules-mill.json
│   └── classpath-mill-root.json
├── modules/app/
│   └── .scala-semantic/
│       ├── modules-mill.json
│       └── classpath-mill-app.json
└── out/modules/app/compile.dest/
    └── .scala-semantic/
        └── classpath.json
```

MCP should consider both likely child metadata locations:

- `<pathFromRoot>/.scala-semantic`
- `<pathToOutDir>/.scala-semantic`

If the child `.scala-semantic/modules-*.json` exists, traversal continues from that child. If only
classpath metadata exists, traversal stops there after collecting it.

## MCP Reader Behavior

1. Resolve active workspace root from the current MCP state.
2. Look for classpath metadata directly in `<root>/.scala-semantic`.
3. Look for module-structure metadata directly in `<root>/.scala-semantic`.
4. For every module entry, visit:
   - `root / pathFromRoot`
   - `root / pathToOutDir`
5. In each visited directory, collect classpath metadata from `.scala-semantic/classpath-*.json`.
6. In each visited directory, read nested `.scala-semantic/modules-*.json` and repeat traversal.
7. Deduplicate by normalized directory and normalized metadata file path.
8. Enforce traversal limits to avoid accidental runaway graphs:
   - max module-structure depth, for example `16`
   - max visited directories, for example `5000`
   - skip hidden directories unless they are the exact `.scala-semantic` metadata directory reached
     from a known module path.
9. Keep explicit classpath argument and `SCALASEMANTIC_CLASSPATH` behavior unchanged.
10. Keep current bounded visible-directory scan as a fallback when no direct or structure-guided
    metadata is found.

Root state caching stays unchanged conceptually: discovered metadata belongs to the active workspace
root and is remembered until `set_workspace_root` changes roots.

## Build Tool Writer Behavior

Add a task alongside the existing classpath writer:

```text
scalaSemanticWriteModules
```

Responsibilities:

- Detect the build tool's current subproject/module graph.
- Write the parent module's `.scala-semantic/modules-<tool>.json`.
- Include each direct child module's `name`, `pathFromRoot`, and `pathToOutDir`.
- Write atomically and skip rewriting when content is unchanged.
- Trigger on module-structure changes, not ordinary source-only compilation.

For parent modules that have their own children, the task should also write that parent module's
local `.scala-semantic/modules-<tool>.json`, so traversal can continue to any depth.

## Build Tool Notes

### sbt

Generated `scala-semantic.sbt` can add a `scalaSemanticWriteModules` task using `ScopeFilter` over
projects. The first implementation can write one root-level modules file for all projects, then add
per-parent files once parent-child relationships are represented reliably.

The preferred trigger is still reload/build-structure refresh. `scalaSemanticWriteClasspath` can
depend on `scalaSemanticWriteModules`, or the `onLoad` hook can run both tasks.

### Mill

Mill has reliable module paths and output paths from each module. Add a root command that writes:

- root `.scala-semantic/modules-mill.json`
- child `.scala-semantic/modules-mill.json` for modules that have nested modules

The existing `compileClasspath` override can keep writing per-module classpath metadata. The modules
writer should be callable explicitly and should be wired into the same dependency/configuration
refresh path used by `scalaSemanticWriteClasspath`.

### Scala CLI

Scala CLI may initially write a single root module entry or no structure file when it cannot expose a
stable module graph. Classpath metadata remains enough for single-module projects.

## Implementation Steps

1. Add module-structure JSON model in the MCP module:
   - parse `modules-*.json`
   - resolve `path_from_root` and `path_to_out_dir` against the active root
   - ignore invalid entries without failing MCP startup
2. Split classpath discovery into two layers:
   - metadata file collection
   - module-structure traversal
3. Teach discovery to collect classpath metadata by recursively following `modules-*.json`.
4. Preserve current direct-root and fallback visible-directory scan behavior.
5. Add MCP unit tests:
   - root modules file points to child source `.scala-semantic`
   - root modules file points to child output-dir `.scala-semantic`
   - nested modules files are traversed multiple levels
   - cycles and duplicate module entries terminate and deduplicate
   - invalid modules metadata degrades to fallback/index-only behavior
6. Add build writer tests:
   - generated sbt config writes modules JSON
   - Mill command writes root and nested modules JSON
   - unchanged content is not rewritten
7. Update docs:
   - integration guide
   - classpath refresh plan
   - tool reference root/classpath discovery note
8. Extend the end-to-end smoke test so the launcher discovers classpath metadata only through a
   parent `modules-*.json` path, proving structure traversal is used.

## Rollout Order

1. Implement MCP reader support first, behind backward-compatible discovery behavior.
2. Add tests for hand-written structure metadata.
3. Add Mill writer for this repository.
4. Add generated sbt writer changes in setup scripts.
5. Update docs and smoke tests.
6. Consider replacing the fallback filesystem scan with structure-only traversal in a later release
   after build-tool writers are broadly available.
