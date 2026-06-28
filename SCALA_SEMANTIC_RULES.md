# Scala Code Rules

For any Scala (`.scala`) source questions, file operations, search, or analysis, use ScalaSemantic MCP tools before shell text tools. 
Preferably compile code before usage, therefore more SclaSemantic functions could be used with better result.

1. **NEVER** use generic text/file-reading, viewing, or searching tools (whether built-in agent tools like `view_file`/`grep_search`, or shell commands like
`rg`/`grep`/`cat`/`sed`) on `.scala` files.
2. **ALWAYS** use the custom tools provided by the `scala-semantic` MCP server:
   * **To read/view the contents of a file**: Use the `annotated_source` MCP tool.
   * **For all other queries** (searching, finding usages, hierarchies, etc.): Select the appropriate tool from the registered `scala-semantic` MCP tools.
3. **Exceptions**: You may use generic tools on non-Scala files (such as `.sbt`, `.json`, `.md`), or if the `scala-semantic` MCP tools are completely
failing/unavailable.
