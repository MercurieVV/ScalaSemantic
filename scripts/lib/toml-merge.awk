# Idempotently merge one `[mcp_servers.<name>]` section into a Codex TOML config, replacing only
# the lines from that header up to (but not including) the next `[` section header. Line-based,
# same algorithm as the scala/python mergeToml — no TOML library needed since we only ever touch
# one whole section we render ourselves.
#
# Invocation:  MCPM_HEADER='[mcp_servers.scala-semantic]' MCPM_FRESH=TEXT awk -f toml-merge.awk existing-file-or-empty
#   MCPM_FRESH: the full replacement section text (no trailing newline).
# Passed via environment (not -v): one-true-awk's -v parser cannot accept a value containing a
# literal embedded newline, which our multi-line section text always has.

BEGIN {
  header = ENVIRON["MCPM_HEADER"]
  fresh = ENVIRON["MCPM_FRESH"]
}

{ lines[++n] = $0 }

END {
  if (n == 0) { printf "%s\n", fresh; exit }

  idx = 0
  for (i = 1; i <= n; i++) {
    t = lines[i]
    gsub(/^[ \t]+/, "", t); gsub(/[ \t]+$/, "", t)
    if (t == header) { idx = i; break }
  }

  if (idx == 0) {
    for (i = 1; i <= n; i++) print lines[i]
    print ""
    printf "%s\n", fresh
    exit
  }

  endi = n + 1
  for (i = idx + 1; i <= n; i++) {
    t = lines[i]
    gsub(/^[ \t]+/, "", t)
    if (substr(t, 1, 1) == "[") { endi = i; break }
  }

  for (i = 1; i < idx; i++) print lines[i]
  printf "%s\n", fresh
  for (i = endi; i <= n; i++) print lines[i]
}
