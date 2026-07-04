#!/usr/bin/env python3
"""Patch a checked-out build.mill in place to add Mill-side Stryker4s mutation support.

Only ever run against the throwaway worktree copy scripts/run-stryker.sh operates in (or --local's
own checkout) — never against the committed build.mill. See docs/MILL_MIGRATION.md §10 item 3 for
why this can't be a permanent, unconditional part of build.mill: the plugin has no Maven Central
release, only a locally-published snapshot (mill-stryker4s_mill1, version 0.0.0-TEST-SNAPSHOT),
and Mill's `//| mvnDeps:` header resolves statically on every invocation — adding it unconditionally
would break every plain `./mill compile` the moment that local-only jar is missing.
"""
import sys

STRYKER_MVN_DEP = "//| - io.stryker-mutator::mill-stryker4s_mill1:0.0.0-TEST-SNAPSHOT"
STRYKER_IMPORT = "import stryker4s.mill.Stryker4sModule"

OVERLAY = '''
// --- Stryker4s mutation testing (Mill-side, generated) ----------------------------------------
// Patched in by scripts/generate-stryker-overlay.py into an isolated worktree copy only — never
// committed. See docs/MILL_MIGRATION.md §10 item 3: mill-stryker4s has no Maven Central release
// yet, only a locally-published snapshot, so it can't live in the real build.mill unconditionally.
// Each `*Stryker` object reuses the real module's mvnDeps/moduleDeps/test module by direct
// reference (no duplication) and only differs by mixing in Stryker4sModule.
// `moduleDir` is overridden to the real module's directory (not the Stryker-object's own
// name-derived one) so sources resolve and the JSON/HTML report lands under the same
// `<module>/target/stryker4s-report/` path scripts/run-stryker.sh and mutation-summary.sh expect.
object coreStryker extends Common with Stryker4sModule {
  def moduleDir = core.moduleDir
  def moduleDeps = core.moduleDeps
  def mvnDeps = core.mvnDeps
  def strykerTestModule = core.test
}
object analysisStryker extends Common with Stryker4sModule {
  def moduleDir = analysis.moduleDir
  def moduleDeps = analysis.moduleDeps
  def mvnDeps = analysis.mvnDeps
  def unmanagedClasspath = analysis.unmanagedClasspath
  def strykerTestModule = analysis.test
}
object mcpStryker extends Common with Stryker4sModule {
  def moduleDir = mcp.moduleDir
  def moduleDeps = mcp.moduleDeps
  def mvnDeps = mcp.mvnDeps
  def strykerTestModule = mcp.test
}
'''


def patch(path: str) -> None:
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    if any("mill-stryker4s_mill1" in line for line in lines):
        return  # already patched (e.g. re-run in a reused worktree)

    last_header = -1
    for i, line in enumerate(lines):
        if line.startswith("//|"):
            last_header = i
    if last_header == -1:
        sys.exit("error: no `//|` header block found at top of build.mill")
    lines.insert(last_header + 1, STRYKER_MVN_DEP + "\n")

    import_idx = next(
        (i for i, line in enumerate(lines) if line.startswith("import mill.contrib.buildinfo.BuildInfo")),
        None,
    )
    if import_idx is None:
        sys.exit("error: could not find import anchor line in build.mill")
    lines.insert(import_idx + 1, STRYKER_IMPORT + "\n")

    lines.append(OVERLAY)

    with open(path, "w", encoding="utf-8") as f:
        f.writelines(lines)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: generate-stryker-overlay.py <path-to-build.mill>")
    patch(sys.argv[1])
