# Idempotently merge one `- name: "<name>"` list item into a Continue `mcpServers:` block,
# matching only that item's own indented span. Line-based, indentation-aware — same algorithm as
# the scala/python mergeYaml — no YAML library needed since we only ever touch one item we render
# ourselves.
#
# Invocation:  MCPM_ITEM_NAME_LINE='- name: "scala-semantic"' MCPM_ITEM=TEXT MCPM_FRESH_FULL=TEXT \
#                  awk -f yaml-merge.awk existing-file-or-empty
#   MCPM_ITEM:      the full replacement item text (no trailing newline), indented to match the file.
#   MCPM_FRESH_FULL: the full file text to write when the input is empty (no trailing newline).
# Passed via environment (not -v): one-true-awk's -v parser cannot accept a value containing a
# literal embedded newline, which our multi-line item text always has.

BEGIN {
  itemNameLine = ENVIRON["MCPM_ITEM_NAME_LINE"]
  item = ENVIRON["MCPM_ITEM"]
  freshFull = ENVIRON["MCPM_FRESH_FULL"]
}

{ lines[++n] = $0 }

END {
  if (n == 0) { printf "%s\n", freshFull; exit }

  msIdx = 0
  for (i = 1; i <= n; i++) {
    t = lines[i]
    gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
    if (t == "mcpServers:") { msIdx = i; break }
  }

  if (msIdx == 0) {
    for (i = 1; i <= n; i++) print lines[i]
    print "mcpServers:"
    printf "%s\n", item
    exit
  }

  blockEnd = n + 1
  for (i = msIdx + 1; i <= n; i++) {
    t = lines[i]
    if (length(t) == 0) continue
    first = substr(t, 1, 1)
    tt = t
    gsub(/^[ \t]+/, "", tt); gsub(/[ \t]+$/, "", tt)
    if (tt != "" && first != " ") { blockEnd = i; break }
  }

  itemIdx = 0
  for (i = msIdx + 1; i < blockEnd; i++) {
    t = lines[i]
    gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
    if (t == itemNameLine) { itemIdx = i; break }
  }

  if (itemIdx == 0) {
    for (i = 1; i <= msIdx; i++) print lines[i]
    printf "%s\n", item
    for (i = msIdx + 1; i <= n; i++) print lines[i]
    exit
  }

  indentLine = lines[itemIdx]
  indent = 0
  while (substr(indentLine, indent + 1, 1) == " ") indent++

  e = blockEnd
  for (i = itemIdx + 1; i < blockEnd; i++) {
    li = lines[i]
    liIndent = 0
    while (substr(li, liIndent + 1, 1) == " ") liIndent++
    trimmed = li
    gsub(/^[ \t]+/, "", trimmed)
    if (liIndent == indent && substr(trimmed, 1, 2) == "- ") { e = i; break }
  }

  for (i = 1; i < itemIdx; i++) print lines[i]
  printf "%s\n", item
  for (i = e; i <= n; i++) print lines[i]
}
