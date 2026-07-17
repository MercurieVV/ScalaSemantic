# Postmortem: `SemanticIndex` skipped `.semanticdb` directories (pruning Scala CLI target roots)

## Summary

During semantic analysis inside `ScalaSemanticMCP`, indexing failed to find any files under Scala CLI's target root `.semanticdb/META-INF/semanticdb/*.semanticdb`. The files were generated correctly on disk, but they were ignored by `SemanticIndex.findSemanticdb` because the traversal implementation aggressively skipped all hidden directories (any directory beginning with a dot `.`).

## Root cause

In `com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex.scala`, the directory traversal uses a custom `FileVisitor` where `preVisitDirectory` determines whether to skip a subdirectory:

```scala
override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
  val skip = Option(dir.getFileName).exists { n =>
    val nameStr = n.toString
    dir != root && nameStr != "." && nameStr != ".." &&
    (nameStr.startsWith(".") || nameStr == "worktrees")
  }
  if skip then FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
```

Because `.semanticdb` begins with `.`, it matched the `nameStr.startsWith(".")` condition. Thus, the traversal returned `FileVisitResult.SKIP_SUBTREE` for `.semanticdb/`, preventing any nested `.semanticdb` files from being discovered or indexed.

Additionally, standard search tools like `rtk find` hid the files by default because they were under a hidden directory, unless explicitly run via `rtk proxy find` (due to Rust-based `rtk` filtering hidden directories/files under the hood). This initially misled us into thinking the `.semanticdb` files were not generated at all.

## Fix

1. **SemanticIndex Modification**: Updated the exclusion logic in `SemanticIndex.scala` to explicitly allow `.semanticdb` while still skipping other hidden cache directories (like `.scala-build`, `.git`, `.idea`, etc.):
   ```scala
   val hiddenCache = nameStr.startsWith(".") && nameStr != ".semanticdb"
   dir != root && nameStr != "." && nameStr != ".." && (hiddenCache || nameStr == "worktrees")
   ```
2. **Regression Test Suite**: Added a test case `loads Scala CLI semanticdb target root under .semanticdb` in `SemanticIndexSuite.scala`. It programmatically constructs a mock `.semanticdb/META-INF/semanticdb/` hierarchy, writes a dummy `.semanticdb` file, and asserts that `SemanticIndex.fingerprint` successfully detects the file (size and presence).

## Verification

- `rtk ./mill core.test` passed.
- `rtk ./mill mcp.test` passed.
- Built a local `mcp.assembly` jar and verified that it correctly sees 9 SemanticDB files in the `gh-tasks-llm-executor` project directory.
