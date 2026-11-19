# Re-record: from the repo root, after ./mill __.compile, run
#   SCALASEMANTIC_JAR=/path/to/scalasemantic-mcp.jar vhs scripts/demo/demo.tape
# DEMO_ROOT (default: cwd) must be a compiled checkout (SemanticDB present), else find_usages reports 0.
# (brew install vhs). Output: website/static/img/demo.gif, embedded in README.md.
Output website/static/img/demo.gif
Set Shell "bash"
Set FontSize 15
Set Width 1300
Set Height 700
Set Padding 16
Set Theme "Dracula"
Set TypingSpeed 35ms

Hide
Type `export PS1='$ ' && cd ${DEMO_ROOT:-$PWD}`
Enter
Type `scalasemantic-mcp() { java -jar $SCALASEMANTIC_JAR "$@"; }`
Enter
Type `PS1='\[\033[1;31m\]text search ▸ \[\033[0;31m\]'`
Enter
Type "clear"
Enter
Show

# Red = plain text search
Type "# Who uses SemanticIndex?"
Enter
Sleep 600ms
Type `git grep -n SemanticIndex -- '*.scala' | head -4 | cut -c1-120`
Enter
Sleep 2s
Type `git grep -n SemanticIndex -- '*.scala' | wc -l`
Enter
Sleep 2s
Type "# 196 hits: imports, comments, SemanticIndexFile..."
Enter
Sleep 3s

# Green = compiler-resolved (SemanticDB)
Hide
Type `PS1='\[\033[1;32m\]semantic ▸ \[\033[0;32m\]'`
Enter
Type "clear"
Enter
Show
Type "# Same question, from the compiler:"
Enter
Sleep 600ms
Type `scalasemantic-mcp call find_usages '{"symbol":"com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex#","limit":3}'`
Enter
Sleep 6s
Type "# 128 exact references, one definition. Paged, no noise."
Enter
Sleep 4s
