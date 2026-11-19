# Re-record: from the repo root, after ./mill __.compile, run
#   SCALASEMANTIC_JAR=/path/to/scalasemantic-mcp.jar vhs scripts/demo/demo.tape
# DEMO_ROOT (default: cwd) must be a compiled checkout (SemanticDB present), else find_usages reports 0.
# (brew install vhs). Output: website/static/img/demo.gif, embedded in README.md.
Output website/static/img/demo.gif
Set Shell "bash"
Set FontSize 15
Set Width 1300
Set Height 640
Set Padding 16
Set Theme "Dracula"
Set TypingSpeed 35ms

Hide
Type `export PS1='$ ' && cd ${DEMO_ROOT:-$PWD}`
Enter
Type `scalasemantic-mcp() { java -jar $SCALASEMANTIC_JAR "$@"; }`
Enter
Type "clear"
Enter
Show

Type "# Who uses SemanticIndex? Text search:"
Enter
Sleep 600ms
Type `git grep -n SemanticIndex -- '*.scala' | wc -l`
Enter
Sleep 2s
Type "# 196 text hits: imports, comments, SemanticIndexFile... Compiler-resolved:"
Enter
Sleep 800ms
Type `scalasemantic-mcp call find_usages '{"symbol":"com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex#","limit":3}'`
Enter
Sleep 6s
Type "# 128 exact references, one definition. Paged, no noise."
Enter
Sleep 4s
