# ScalaSemantic

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mercurievv/scalasemantic-core_3?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.mercurievv)
[![CI](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml/badge.svg)](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-site-blue)](https://mercurievv.github.io/ScalaSemantic/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**ScalaSemantic** is an MCP server that gives AI coding agents compiler-resolved Scala facts — exact symbols, types, inheritance, usages, implicits, and call paths — from compiler-emitted **SemanticDB**. Instead of grepping source text, agents query what the compiler already knows.

Works with Scala 2.13.* and 3.*.*, any sbt/Mill/Gradle project, and any MCP-compatible agent (Claude Code, Codex, Gemini CLI, Cline, Roo Code, Continue…).

## Quick setup

Needs only `java`. One command registers the server for your user, so every Scala project on the machine gets it:

```sh
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh
```

Run it from a project root with `--project` to also enable SemanticDB, write the agent steering files and install the Claude guard hook. Re-running is safe:

```sh
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh | sh -s -- --project
```

```powershell
iwr https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.ps1 -OutFile scalasemantic-mcp.ps1; .\scalasemantic-mcp.ps1 setup
```

Launcher, plain jar and logging: [Integration](https://mercurievv.github.io/ScalaSemantic/getting-started/integration/). The optional [guard hook](https://mercurievv.github.io/ScalaSemantic/adr/claude-code-guard-hook/) denies `grep`/`cat`/`Read` on `.scala` files and points agents at the MCP tools; it is not installed by default.

## Tools

`find_symbol` · `find_usages` · `class_hierarchy` · `method_signature` · `members` · `resolve_implicits` · `call_path` · `type_at_position` · `trace_implicit_chain` · `find_overloads` — what each does, plus the symbol grammar: [Tool reference](https://mercurievv.github.io/ScalaSemantic/reference/tools/).

## SemanticDB vs `grep`

| Question | Right tool |
|---|---|
| Exact callers of a method | `find_usages` |
| All subtypes of a trait | `class_hierarchy` |
| Which `given` satisfies a type | `resolve_implicits` |
| Call path from method `a` to `c` | `call_path` |
| Comments, TODOs, config files | `grep` |
| Code that hasn't compiled yet | `grep` |

Measured: semantic tools use ~90% fewer tokens than grep for symbol questions. Details: [ScalaSemantic vs grep](https://mercurievv.github.io/ScalaSemantic/explanation/scala-semantic-vs-grep/).

## Documentation

- [Quickstart](https://mercurievv.github.io/ScalaSemantic/getting-started/quickstart/) — install in 5 minutes
- [Integration](https://mercurievv.github.io/ScalaSemantic/getting-started/integration/) — launcher, plain jar, logging
- [Tool reference](https://mercurievv.github.io/ScalaSemantic/reference/tools/) — all MCP tools and symbol grammar
- [Tool examples](https://mercurievv.github.io/ScalaSemantic/usage/tool-examples/) — real MCP calls and responses
- [FAQ](https://mercurievv.github.io/ScalaSemantic/getting-started/faq/) — compile freshness, Metals, install choices
- [Development](https://mercurievv.github.io/ScalaSemantic/project/development/) — modules, build, test
- [Releasing](https://mercurievv.github.io/ScalaSemantic/project/releasing/) — Sonatype Central process

Full documentation: **[mercurievv.github.io/ScalaSemantic](https://mercurievv.github.io/ScalaSemantic/)**

## License

[MIT](LICENSE)
