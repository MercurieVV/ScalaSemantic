# Steering LLM Agents Toward ScalaSemantic

This report addresses GitHub issue [#56](https://github.com/MercurieVV/ScalaSemantic/issues/56): **"Investigate a universal way to steer popular LLMs toward ScalaSemantic."**

The goal is to determine the best cross-LLM patterns for making agents (like Claude Code, Cursor, Copilot, and Gemini) prefer ScalaSemantic MCP/tool usage over generic text search (like `grep` or `ripgrep`).

---

## 1. LLM-Specific Solutions

Most major AI coding assistants and IDEs have custom mechanisms to read project-level rules from the workspace root. Below is a breakdown of the leading solutions:

### Claude Code (CLI)
* **Configuration Surface**: `CLAUDE.md`
* **How it works**: Claude Code automatically reads `CLAUDE.md` in the project root on startup. It is treated as a "standing brief." The tool also supports user-level configurations via `~/.claude/CLAUDE.md` and directory-level rules (`path/to/subfolder/CLAUDE.md`).
* **Steering Strategy**: Add a dedicated section in `CLAUDE.md` outlining tool usage boundaries (e.g., "For Scala code exploration, use ScalaSemantic MCP tools over `grep`").

### Cursor (IDE)
* **Configuration Surface**: `.cursor/rules/*.mdc` (Modular rules) or `.cursorrules` (Legacy)
* **How it works**: Cursor reads `.cursorrules` from the root, or modular markdown rules inside `.cursor/rules/` ending in `.mdc`. `.mdc` files are highly powerful because they support frontmatter with glob matches (e.g., only trigger rule when editing/querying `.scala` files).
* **Steering Strategy**: Create `.cursor/rules/scala-semantic.mdc` targeting `glob: "**/*.scala"` to automatically instruct Cursor to prefer the MCP tools when editing Scala files.

### GitHub Copilot (VS Code / JetBrains / CLI)
* **Configuration Surface**: `.github/copilot-instructions.md`
* **How it works**: Copilot Chat in IDEs reads this file to customize the developer instructions and project context.
* **Steering Strategy**: Detail ScalaSemantic tool preference in `.github/copilot-instructions.md`.

### Windsurf (IDE)
* **Configuration Surface**: `.windsurfrules`
* **How it works**: Works similarly to `.cursorrules` in the project root.
* **Steering Strategy**: Add code search preferences to `.windsurfrules`.

### Cline / Roo Code / Roo Cline (VS Code Extensions)
* **Configuration Surface**: `.clinerules` (Cline) or custom modes / `.roomodes.json` (Roo Code).
* **How it works**: Reads rules on initialization to steer system behavior.
* **Steering Strategy**: Place instructions in `.clinerules`.

### Google Antigravity / Gemini CLI
* **Configuration Surface**: `AGENTS.md`
* **How it works**: Configured in global (`~/.gemini/config/AGENTS.md`) or workspace (`.agents/AGENTS.md` or root `AGENTS.md`) roots.
* **Steering Strategy**: Include instructions directly or reference external files (e.g., `<INSTRUCTIONS> @SCALA_CODE_RULES.md </INSTRUCTIONS>`).

---

## 2. Generic / Universal Solutions

Two client-agnostic approaches exist to bypass fragmentation:

### A. The MCP Protocol `instructions` Field
Under the Model Context Protocol (MCP) spec, the server can return an `instructions` field (string) inside the `InitializeResult` during the handshake (connection setup).
* **How it works**: The client (e.g., Claude Desktop, Roo Code, Cursor) receives these instructions on connection and automatically injects them into the LLM's **system prompt**.
* **Advantages**:
  * **Zero Repo Overhead**: No dotfiles required in target repositories. Once the user adds the MCP server, the model is automatically instructed on how to use it.
  * **Centralized Logic**: Server developers control the exact steering instructions.
* **Limitations**: Depends on the client actually forwarding the `instructions` field to the LLM (some clients or older SDKs may ignore it).

### B. The "Master Rules File" Pattern (`AGENTS.md`)
Because of client fragmentation, a common community pattern is to maintain a single, vendor-neutral rules file in the workspace root.
* **How it works**: A centralized file like `AGENTS.md` or `GUIDELINES.md` acts as the single source of truth. Individual tool configurations (like `.cursorrules` or `.clinerules`) then reference this file or act as thin symlinks/copies.
* **Advantages**: Consistent guidelines regardless of the IDE or tool the developer uses.

---

## 3. Support Matrix

| Tool / Surface | Config File / Mechanism | Scope | MCP `instructions` Support |
| :--- | :--- | :--- | :--- |
| **Claude Code** | `CLAUDE.md` | Workspace / Folder | Yes |
| **Cursor** | `.cursor/rules/*.mdc`, `.cursorrules` | Workspace | Yes |
| **GitHub Copilot** | `.github/copilot-instructions.md` | Workspace | No (uses internal prompting) |
| **Gemini / Antigravity** | `AGENTS.md` | Workspace / Global | Yes |
| **Windsurf** | `.windsurfrules` | Workspace | Yes |
| **Cline / Roo Code** | `.clinerules` | Workspace | Yes |

---

## 4. Recommendation for ScalaSemantic

To consistently steer AI agents toward ScalaSemantic tools, the project should adopt a **two-pronged strategy**:

1. **Keep the Server-Level `instructions` Robust**:
   Continue returning detailed usage guidance in the MCP `initialize` response (`Mcp.scala`). This provides out-of-the-box steering for all compliant clients (such as Claude Desktop and Roo Code) without needing repo-level changes.
   
2. **Establish a Symlinked / Templated Root Rules Layout**:
   Provide a centralized rules document like `SCALA_CODE_RULES.md` (or `AGENTS.md`), and document how users can reference it.
   For users setting up ScalaSemantic in their own projects:
   * **Claude Code**: The `sbt` plugin or setup guide can offer to append rules to the local `CLAUDE.md`.
   * **Cursor**: Provide a template `.cursorrules` or `.cursor/rules/scala-semantic.mdc` pointing to the ScalaSemantic tools.
   * **Copilot**: Provide a template snippet for `.github/copilot-instructions.md`.
