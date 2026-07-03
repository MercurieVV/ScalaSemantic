# Idempotently merge one `"<server>": { ... }` entry into a JSON MCP client config, under its
# `"mcpServers"` object. Only that one entry is touched; every other key in the file survives
# byte-for-byte. Ported from the bracket-matching JSON merge in scalasemantic-mcp.scala so the
# POSIX-sh launcher needs no external interpreter (no python3, no jq) — just awk.
#
# Invocation:  MCPM_SERVER=NAME MCPM_ENTRY=TEXT awk -f json-merge.awk existing-file-or-empty
#   MCPM_ENTRY: the exact (possibly multi-line) JSON text to place as the value of "<server>".
# Passed via environment (not -v): one-true-awk's -v parser cannot accept a value containing a
# literal embedded newline, which our multi-line entry text always has.

BEGIN {
  server = ENVIRON["MCPM_SERVER"]
  entry = ENVIRON["MCPM_ENTRY"]
}

function indexOfCharFrom(s, from, ch,    i, n) {
  n = length(s)
  for (i = from; i <= n; i++) if (substr(s, i, 1) == ch) return i
  return -1
}

function skipWs(s, i, limit,    c) {
  while (i <= limit) {
    c = substr(s, i, 1)
    if (c == " " || c == "\n" || c == "\t" || c == "\r") i++
    else break
  }
  return i
}

function trimStr(s,   t) {
  t = s
  gsub(/^[ \t\r\n]+/, "", t)
  gsub(/[ \t\r\n]+$/, "", t)
  return t
}

# Returns the index of the bracket matching the one at openIdx, string- and escape-aware.
function matchBracket(s, openIdx,    i, n, depth, inStr, esc, c) {
  n = length(s)
  depth = 0; inStr = 0; esc = 0
  for (i = openIdx; i <= n; i++) {
    c = substr(s, i, 1)
    if (inStr) {
      if (esc) { esc = 0 }
      else if (c == "\\") { esc = 1 }
      else if (c == "\"") { inStr = 0 }
    } else {
      if (c == "\"") inStr = 1
      else if (c == "{" || c == "[") depth++
      else if (c == "}" || c == "]") { depth--; if (depth == 0) return i }
    }
  }
  return -1
}

# Finds a `"key":` at brace-depth 0 within [start, end]; returns the index of the opening quote.
function findKey(s, start, end, key,    target, tlen, i, depth, inStr, esc, c, j) {
  target = "\"" key "\""
  tlen = length(target)
  depth = 0; inStr = 0; esc = 0
  for (i = start; i <= end; i++) {
    c = substr(s, i, 1)
    if (inStr) {
      if (esc) { esc = 0 }
      else if (c == "\\") { esc = 1 }
      else if (c == "\"") { inStr = 0 }
    } else {
      if (c == "\"") {
        if (depth == 0 && substr(s, i, tlen) == target) {
          j = skipWs(s, i + tlen, end)
          if (substr(s, j, 1) == ":") return i
        }
        inStr = 1
      } else if (c == "{" || c == "[") depth++
      else if (c == "}" || c == "]") depth--
    }
  }
  return -1
}

# Index just past the value starting at vs (object/array/string/bare-token), trimmed of trailing ws.
function jsonValueEnd(s, vs, limit,    c, i, esc, j) {
  c = substr(s, vs, 1)
  if (c == "{" || c == "[") return matchBracket(s, vs) + 1
  if (c == "\"") {
    i = vs + 1; esc = 0
    while (i <= limit) {
      c = substr(s, i, 1)
      if (esc) { esc = 0; i++; continue }
      if (c == "\\") { esc = 1; i++; continue }
      if (c == "\"") return i + 1
      i++
    }
    return limit
  }
  i = vs
  while (i <= limit) {
    c = substr(s, i, 1)
    if (c == "," || c == "}") break
    i++
  }
  j = i
  while (j > vs) {
    c = substr(s, j - 1, 1)
    if (c == " " || c == "\n" || c == "\t" || c == "\r") j--
    else break
  }
  return j
}

{ content = content $0 "\n" }

END {
  if (trimStr(content) == "") {
    printf "{\n  \"mcpServers\": {\n    \"%s\": %s\n  }\n}\n", server, entry
    exit
  }

  rootOpen = index(content, "{")
  if (rootOpen == 0) { printf "%s", content; exit }
  rootClose = matchBracket(content, rootOpen)
  if (rootClose < 0) { printf "%s", content; exit }

  msKey = findKey(content, rootOpen + 1, rootClose - 1, "mcpServers")
  if (msKey < 0) {
    hadEntries = (trimStr(substr(content, rootOpen + 1, rootClose - rootOpen - 1)) != "")
    block = "\n  \"mcpServers\": {\n    \"" server "\": " entry "\n  }"
    comma = hadEntries ? "," : ""
    printf "%s%s%s%s", substr(content, 1, rootOpen), block, comma, substr(content, rootOpen + 1)
    exit
  }

  colonPos = indexOfCharFrom(content, msKey, ":")
  objOpen = indexOfCharFrom(content, colonPos, "{")
  objClose = matchBracket(content, objOpen)

  snKey = findKey(content, objOpen + 1, objClose - 1, server)
  if (snKey >= 0) {
    colon2 = indexOfCharFrom(content, snKey, ":")
    vs = skipWs(content, colon2 + 1, objClose)
    ve = jsonValueEnd(content, vs, objClose)
    printf "%s%s%s", substr(content, 1, vs - 1), entry, substr(content, ve)
  } else {
    hadEntries = (trimStr(substr(content, objOpen + 1, objClose - objOpen - 1)) != "")
    ins = "\n    \"" server "\": " entry (hadEntries ? "," : "")
    printf "%s%s%s", substr(content, 1, objOpen), ins, substr(content, objOpen + 1)
  }
}
