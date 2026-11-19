# ScalaSemantic

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mercurievv/scalasemantic-core_3?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.mercurievv/scalasemantic-core_3)
[![Release](https://img.shields.io/github/v/release/MercurieVV/ScalaSemantic)](https://github.com/MercurieVV/ScalaSemantic/releases/latest)
[![CI](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml/badge.svg)](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-site-blue)](https://mercurievv.github.io/ScalaSemantic/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**ScalaSemantic** is an MCP server that gives AI coding agents compiler-resolved Scala facts — exact symbols, types, inheritance, usages, implicits, and call paths — from compiler-emitted **SemanticDB**. Instead of grepping source text, agents query what the compiler already knows.

Works with Scala 2.13.* and 3.*.*, any sbt/Mill/Gradle project, and any MCP-compatible agent (Claude Code, Codex, Gemini CLI, Cline, Roo Code, Continue…).

## Why

![Text search vs find_usages on this repo: 196 text hits vs 128 compiler-resolved references](website/static/img/demo.gif)

Agents navigate Scala by grepping text. That misses renamed imports, re-exports, inferred types and summoned `given`s, and floods the context with false hits. ScalaSemantic answers from what the compiler already resolved:

```text
agent → find_usages("com.acme.Repo#save().")
      ← 4 exact call sites (uri:line:col), no comments, no same-named look-alikes
```

One compact, exact answer instead of dozens of text matches to read and filter — measured ~90% fewer tokens for symbol questions (see [benchmarks](https://mercurievv.github.io/ScalaSemantic/explanation/scala-semantic-vs-grep/)).

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

| Group | Tools |
|---|---|
| Find | `find_symbol` · `find_usages` · `find_overloads` · `search_text` |
| Types & hierarchy | `class_hierarchy` · `members` · `method_signature` · `type_at_position` |
| Implicits | `resolve_implicits` · `trace_implicit_chain` · `hole_context` |
| Flow | `call_path` · `value_flow` · `resolve_references` |
| Read | `annotated_source` · `document_outline` · `structure` |
| Refactor plans | `rename_plan` · `batch_rename_plan` · `move_plan` · `extract_method_plan` |
| Analysis | `smart_code_duplications` · `literal_tokens` · `contract_clauses` |
| Session | `set_workspace_root` · `get_workspace_root` · `refresh_workspace` |

What each does, plus the symbol grammar: [Tool reference](https://mercurievv.github.io/ScalaSemantic/reference/tools/).

For a shell hook or script that can't hold an MCP session open, `scalasemantic-mcp call <tool> '<json-args>' [root] [classpath]` runs one tool and prints its JSON result.

## SemanticDB vs `grep`

| Question | Right tool |
|---|---|
| Exact callers of a method | `find_usages` |
| All subtypes of a trait | `class_hierarchy` |
| Which `given` satisfies a type | `resolve_implicits` |
| Call path from method `a` to `c` | `call_path` |
| Comments, TODOs, config files | `grep` |
| Code that hasn't compiled yet | `grep` |

Details: [ScalaSemantic vs grep](https://mercurievv.github.io/ScalaSemantic/explanation/scala-semantic-vs-grep/). Background reading: [It Hurts to Watch an AI Grep My Scala](docs/articles/it-hurts-to-watch-ai-grep-my-scala.md), [Measuring AI agent token costs and reliability](docs/articles/measuring-ai-agent-token-costs-and-reliability.md).

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
