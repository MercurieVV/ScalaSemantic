# Steering LLM Agents Toward ScalaSemantic

Addresses GitHub issue [#56](https://github.com/MercurieVV/ScalaSemantic/issues/56): finding a universal way to steer agents toward ScalaSemantic instead of grep.

## Client-specific config surfaces

| Tool | Config file | MCP `instructions` support |
|---|---|---|
| Claude Code | `CLAUDE.md` (also `~/.claude/CLAUDE.md`) | Yes |
| Cursor | `.cursor/rules/*.mdc` or `.cursorrules` | Yes |
| GitHub Copilot | `.github/copilot-instructions.md` | No (internal prompting) |
| Gemini / Antigravity | `AGENTS.md` | Yes |
| Windsurf | `.windsurfrules` | Yes |
| Cline / Roo Code | `.clinerules` | Yes |

`sbt mcpClientConfig` generates the right file for each client and also writes `SCALA_SEMANTIC_RULES.md` with steering instructions, then references it from each client's config file.

## Universal approaches

**MCP `initialize` instructions field.** The server returns an `instructions` string in `InitializeResult`. Compliant clients inject this into the system prompt — no dotfiles needed in the target repo. This is the primary mechanism; it works out-of-the-box as long as the client respects the field.

**Master rules file (`SCALA_SEMANTIC_RULES.md`).** A single vendor-neutral rules file in the project root, referenced by all client-specific configs. Consistent guidelines regardless of which IDE the developer uses.

## Recommendation

Two-pronged:

1. **Keep the server-level `instructions` robust** — provides out-of-the-box steering for all compliant clients without requiring repo-level changes.
2. **Generate `SCALA_SEMANTIC_RULES.md` + client config stubs via `sbt mcpClientConfig`** — covers clients that don't forward MCP instructions and provides a human-readable source of truth. Already implemented.
