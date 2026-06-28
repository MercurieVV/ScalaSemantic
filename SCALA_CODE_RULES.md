# Scala Code Rules

For Scala source questions, use ScalaSemantic MCP tools before shell text tools. Preferably compile code before usage, then moreSclaSemantic functions could be used with better result.

Do not use `cat`, `sed`, `rg`, or similar tools to inspect `.scala` files for symbol, type,
signature, hierarchy, implicit, reference, or call-path questions when ScalaSemantic tools are
available.

Use shell for builds, tests, git, config, docs, scripts, and non-Scala text work.

