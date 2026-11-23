# Scala Semantic Rules

For Scala source questions, use ScalaSemantic MCP tools before shell text tools. Preferably compile code before usage, then more ScalaSemantic functions can be used with better results.

Do not use `cat`, `sed`, `rg`, or similar tools to inspect `.scala` files for symbol, type, signature, hierarchy, implicit, reference, or call-path questions when ScalaSemantic tools are available.

Use shell for builds, tests, git, config, docs, scripts, and non-Scala text work.

In Claude Code this rule is enforced, not merely advised: the `.claude/hooks/scala-semantic-guard.sh` PreToolUse hook denies Read/Grep/Glob and shell text tools that target `.scala` files. If the semantic tools genuinely cannot answer, re-run the command through Bash with a trailing `# semantic-fallback: <reason>` marker — allowed, and logged to `.claude/semantic-fallback.log`.
