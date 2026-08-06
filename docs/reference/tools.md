# Tool reference

ScalaSemantic exposes MCP tools over stdio JSON-RPC. For most workflows, start with `find_symbol` to turn a plain name into a SemanticDB symbol string, then pass that symbol to the more specific query.

## Tools

| Tool | Use it for |
| --- | --- |
| `find_symbol` | Resolve a plain or partial name to SemanticDB symbol strings. Start here. |
| `find_usages` | Find exact references to a symbol, split by definition/reference, with paging. For a case class it also returns `related` groups — construction sites (which resolve to the companion object, not the class), `copy`, `apply`/`unapply` and parameter accessors. |
| `method_signature` | Render a method signature, including type params and implicit/using parameter lists. |
| `class_hierarchy` | Inspect parents, linearization, and known subtypes across the index. |
| `find_overloads` | List overloads that share a name and owner. |
| `members` | List declared and inherited members, override-aware. |
| `type_at_position` | Return the symbol and type at a 0-based source position. |
| `resolve_implicits` | Find given definitions that can produce a requested type. |
| `trace_implicit_chain` | Follow a given's transitive implicit dependencies. |
| `call_path` | Find the shortest known call path between two methods. |
| `annotated_source` | Read a source file with compiler-inserted type annotations. |
| `document_outline` | Survey a file's declarations (kind, line, signature) without reading the full source. |
| `rename_plan` | Produce the exact edits to rename a symbol everywhere it is used. |
| `move_plan` | Produce the edits to move a top-level definition to another package. |
| `extract_method_plan` | Produce the edits to extract a code range into a new method. |
| `set_workspace_root` | Update the session's current workspace root after a worktree or cwd change. |
| `get_workspace_root` | Report the session's current workspace root. |

Results are lean by default: compact locations, one-line signatures, omitted empty fields. Use `"detailed": true` on tools that support it for structured breakdowns. `find_usages` is paged with `limit` and `offset`.

After changing working directories, call `set_workspace_root` with the new absolute path before any
other ScalaSemantic tool. If the current state is unclear, call `get_workspace_root` first. This
works around stdio MCP clients that keep the same server process alive across cwd/worktree changes.
The active root also controls classpath discovery: the server remembers discovered
`.scala-semantic/classpath-*.json` metadata for that root until `set_workspace_root` changes the
root. Discovery checks direct root metadata, follows `.scala-semantic/modules-*.json` to child
source and output directories, then scans visible subdirectories as a fallback.

## SemanticDB symbol grammar

Every semantic tool takes a SemanticDB **symbol string**. The trailing character encodes the kind:

| Terminator | Kind | Example |
|---|---|---|
| `/` | Package | `com/example/` |
| `#` | Type (class/trait/object) | `com/example/Animal#` |
| `.` | Term (val/object) | `com/example/Sample.` |
| `().` | Method | `com/example/Sample.render().` |
| `(+1).` | Overloaded method (disambiguator) | `com/example/Sample.render(+1).` |

So `com/example/Animal#` is the type `Animal` in package `com.example`, and `com/example/Sample.render().` is a method.

Use `find_symbol` to resolve a human name to its symbol; use `type_at_position` to get the symbol at a cursor location.

## Request shape

MCP clients call tools automatically. For manual stdio checks, direct integrations, and full request/response JSON examples for every tool, see <a href="../../usage/tool-examples">Tool Examples</a>.
