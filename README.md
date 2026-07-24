# ScalaSemantic

[![Maven Central](https://img.shields.io/maven-central/v/io.github.mercurievv/scalasemantic-core_3?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.mercurievv)
[![CI](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml/badge.svg)](https://github.com/mercurievv/ScalaSemantic/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-site-blue)](https://mercurievv.github.io/ScalaSemantic/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**ScalaSemantic** is an MCP server that gives AI coding agents compiler-resolved Scala facts — exact symbols, types, inheritance, usages, implicits, and call paths — over compiler-emitted **SemanticDB**. Instead of grepping source text, agents query what the compiler already knows.

Works with Scala 2.13.* and 3.*.*, any sbt/Mill/Gradle project, and any MCP-compatible agent (Claude Code, Codex, Gemini CLI, Cline, Roo Code, Continue…).

## Quick setup

Needs only `java` (no sbt, no Scala CLI). Download the launcher once and run `setup`. It
idempotently enables SemanticDB, writes the agent steering files, and merges an MCP server entry
into every client config it finds — re-running is always safe:

```sh
curl -fsSL https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.sh -o scalasemantic-mcp.sh && chmod +x scalasemantic-mcp.sh && ./scalasemantic-mcp.sh setup
```

```powershell
iwr https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.ps1 -OutFile scalasemantic-mcp.ps1; .\scalasemantic-mcp.ps1 setup
```

If you already have Scala CLI installed, the equivalent `setup` is also available as a script:

```sh
scala-cli https://raw.githubusercontent.com/MercurieVV/ScalaSemantic/master/scripts/scalasemantic-mcp.scala setup
```

### Guard hook (Claude Code)

For Claude Code, `setup` also installs `.claude/hooks/scala-semantic-guard.sh` and registers it as a
`PreToolUse` hook. Steering files only ask; a hook enforces.

It denies exactly one thing: **text search over Scala sources** — `Grep`/`Glob` scoped to Scala, and
`grep`/`egrep`/`fgrep`/`rg`/`ag`/`ack` invoked on a `.scala`/`.sc`/`.mill` path or on a `scala`
source root. Search fails invisibly (misses renames, re-exports, inferred uses; over-matches
comments), and `search_text` is an exact in-MCP replacement, so there is no legitimate reason to
shell out for it.

Nothing else is denied. Reading, editing, writing and running Scala files all stay allowed, because
those commands usually aren't source inspection at all — `cat > New.scala <<EOF` writes,
`cat x.sc | scala-cli -` runs, `sed -i` edits, and an uncompiled `.sc` has no SemanticDB for any MCP
tool to answer from. A wrong denial costs more than a missed nudge: it removes a working tool and
teaches the agent the guard is noise.

It also fails open when the semantic answer isn't available (no MCP entry, no compiled
`*.semanticdb`, no `jq`/`python3`), and an explicit `# semantic-fallback: <reason>` marker on a shell
command always passes (and is logged to `.claude/semantic-fallback.log`).

Skip it with `setup --no-guard` (`-NoGuard` on PowerShell). Rationale and alternatives:
[docs/adr/0001-claude-code-guard-hook.md](docs/adr/0001-claude-code-guard-hook.md).

## Tools

| Tool | Purpose |
|---|---|
| `find_symbol` | Resolve a name to a SemanticDB symbol |
| `find_usages` | Exact references to a symbol (paged) |
| `class_hierarchy` | Parents and known subtypes |
| `method_signature` | Full signature with type/implicit params |
| `members` | Declared and inherited members |
| `resolve_implicits` | Given definitions for a type |
| `call_path` | Shortest call route between two methods |
| `type_at_position` | Symbol and type at a source position |
| `trace_implicit_chain` | Transitive given dependencies |
| `find_overloads` | All overloads sharing a name and owner |

Full reference: [docs/reference/tools.md](docs/reference/tools.md)

## SemanticDB vs `grep`

| Question | Right tool |
|---|---|
| Exact callers of a method | `find_usages` |
| All subtypes of a trait | `class_hierarchy` |
| Which `given` satisfies a type | `resolve_implicits` |
| Call path from method `a` to `c` | `call_path` |
| Comments, TODOs, config files | `grep` |
| Code that hasn't compiled yet | `grep` |

Measured: semantic tools use ~90% fewer tokens than grep for symbol questions.
Details: [SemanticDB vs grep](docs/explanation/scala-semantic-vs-grep.md).

## Documentation

- [**Quickstart**](docs/getting-started/quickstart.md) — auto-download script, 5 minutes
- [**Integration**](docs/getting-started/integration.md) — Scala CLI script, launcher, plain jar, logging
- [**Tool reference**](docs/reference/tools.md) — all MCP tools and SemanticDB symbol grammar
- [**Examples**](docs/usage/examples.md) — sample MCP calls and responses
- [**SemanticDB vs grep**](docs/explanation/scala-semantic-vs-grep.md) — trade-offs and token savings
- [**FAQ**](docs/getting-started/faq.md) — compile freshness, Metals, install choices
- [**Development**](docs/project/development.md) — modules, build, test, cross-version
- [**Releasing**](docs/project/releasing.md) — Sonatype Central release process

Full documentation map: [docs/index.md](docs/index.md)

## License

[MIT](LICENSE)
