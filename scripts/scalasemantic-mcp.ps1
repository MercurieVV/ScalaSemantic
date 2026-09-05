# Default installer + launcher for the ScalaSemantic MCP server (Windows / PowerShell). Two
# subcommands:
#
#   scalasemantic-mcp.ps1 setup [-Project DIR] [-ClientName all|claude|codex|gemini|cline|roo|continue|antigravity]
#       Idempotently: enables SemanticDB in the target project's sbt build, writes/updates
#       SCALA_SEMANTIC_RULES.md + the per-client steering file (CLAUDE.md/AGENTS.md/.cursorrules/...),
#       prefetches (downloads+caches) the server jar, and merges an MCP server entry into each
#       client's config file (.mcp.json, .codex/config.toml, .gemini/settings.json, ...) — re-running
#       is safe, it only ever touches the "scala-semantic" entry.
#   scalasemantic-mcp.ps1 serve <semanticdb-root> [classpath]   (also the default with no subcommand)
#       Runs the server. Two paths, picked automatically:
#         1. if `cs` (coursier) is on PATH — resolve + cache the artifact from Maven Central and run it;
#         2. otherwise — download the fat jar from the latest GitHub Release once (cached by version)
#            and run it with `java -jar`.
# Download progress is suppressed so stdout carries only the JSON-RPC stream.
#
# Cold-start strategy (jar path): once ANY version is cached, a launch serves the newest cached jar
# IMMEDIATELY and forks a detached background updater that fetches the latest release for the NEXT
# launch. So the download never races the client's connect timeout after the first time. The very
# first launch (empty cache) still blocks on the download — run `setup` (or plain `-Prefetch`) once
# to warm the cache ahead of the first real connect.
#
#   -Prefetch  Download + cache the artifact, then exit WITHOUT serving.
#   All other arguments to `serve` are forwarded verbatim to the server: arg 1 = SemanticDB root,
#   optional arg 2 = an explicit compile classpath metadata file. By default the server discovers
#   project-local `.scala-semantic/classpath-*.json` metadata from the active root or its submodules.
#   --log / --log-output  Forwarded to the server to turn on its (off-by-default) file log:
#               --log writes a startup line + one line per tool call; --log-output additionally
#               logs each JSON-RPC response sent to the LLM. (Env equivalents: SCALASEMANTIC_LOG,
#               SCALASEMANTIC_LOG_OUTPUT; log file path via SCALASEMANTIC_LOG_FILE.)
# Requires: java on PATH (and optionally coursier). No sbt, no python — config merging uses only
# built-in PowerShell JSON/text handling.
#
# Pin a version instead of "latest" with:  $env:SCALASEMANTIC_VERSION = "v0.1.4"  (pinned launches
# skip the background updater — you get exactly that version).
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # keep Invoke-WebRequest's progress bar off the streams

$Repo     = 'MercurieVV/ScalaSemantic'
$Org      = 'io.github.mercurievv'
$Artifact = 'scalasemantic-mcp_3'
$Main     = 'com.github.mercurievv.scalasemantic.mcpServer'
$Cache    = Join-Path $env:LOCALAPPDATA 'scalasemantic-mcp'
$Self     = $PSCommandPath

New-Item -ItemType Directory -Force -Path $Cache | Out-Null

# newest cached fat jar (most recently modified), or $null if none.
function Get-NewestCached {
  Get-ChildItem -Path $Cache -Filter 'scalasemantic-mcp-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}

# resolve the release tag to use: explicit pin, else the latest release's tag_name (may be $null).
function Resolve-Tag {
  if ($env:SCALASEMANTIC_VERSION) { return $env:SCALASEMANTIC_VERSION }
  try { (Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest").tag_name } catch { $null }
}

# Download release $Tag's fat jar into the cache if not already present. Resumable + atomic: writes
# a `.tmp` (byte-range resume) and renames only on a complete download. $true on success/cached.
function Get-Release([string]$Tag) {
  $jar = Join-Path $Cache "scalasemantic-mcp-$Tag.jar"
  if (Test-Path $jar) { return $true }
  $url = "https://github.com/$Repo/releases/download/$Tag/scalasemantic-mcp.jar"
  $tmp = "$jar.tmp"
  [Console]::Error.WriteLine("scalasemantic-mcp: downloading $Tag ...")
  # Resume a partial .tmp from a previous (e.g. timed-out) attempt via a byte-range request.
  $headers = @{}
  if (Test-Path $tmp) { $have = (Get-Item $tmp).Length; if ($have -gt 0) { $headers['Range'] = "bytes=$have-" } }
  try {
    Invoke-WebRequest $url -Headers $headers -OutFile $tmp -PassThru | Out-Null
    Move-Item -Force $tmp $jar
    return $true
  } catch {
    # HTTP 416 (Range Not Satisfiable) means the .tmp is already complete — accept it as done.
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 416) {
      Move-Item -Force $tmp $jar; return $true
    }
    [Console]::Error.WriteLine("scalasemantic-mcp: download incomplete ($($_.Exception.Message)); will resume on next launch")
    return $false
  }
}

# ----------------------------------------------------------------------------------------------
# serve: run the server (coursier or cached fat jar). $Rest is forwarded verbatim to the server.
# Returns the jar's exit code (or throws on unresolvable/offline-without-cache).
# ----------------------------------------------------------------------------------------------
function Serve-Main {
  param([string[]]$Rest = @())

  # --bg-fetch: internal mode forked (detached) by a cached-serve launch to fetch the latest release
  # for the NEXT launch, then exit. A lock file keeps concurrent launches from racing the same `.tmp`.
  if ($Rest.Count -ge 1 -and $Rest[0] -eq '--bg-fetch') {
    $lock = Join-Path $Cache '.bgfetch.lock'
    try {
      $fs = [System.IO.File]::Open($lock, 'CreateNew', 'Write', 'None')
      try {
        $tag = Resolve-Tag
        if ($tag) { [void](Get-Release $tag) }
      } finally { $fs.Close(); Remove-Item $lock -ErrorAction SilentlyContinue }
    } catch { } # lock held by another launch — let it do the update
    return
  }

  # -Prefetch (or legacy --prefetch): warm the cache and return, never serve.
  $Prefetch = $false
  if ($Rest.Count -ge 1 -and $Rest[0] -in @('--prefetch', '-Prefetch')) {
    $Prefetch = $true
    $Rest = Slice-Array $Rest 1 ($Rest.Count - 1)
  }

  # --- path 1: coursier (preferred) --------------------------------------------------------------
  if (Get-Command cs -ErrorAction SilentlyContinue) {
    $ver = if ($env:SCALASEMANTIC_VERSION) { $env:SCALASEMANTIC_VERSION -replace '^v', '' } else { 'latest.release' }
    if ($Prefetch) {
      [Console]::Error.WriteLine("scalasemantic-mcp: prefetching ${Org}:${Artifact}:$ver via coursier")
      & cs fetch "${Org}:${Artifact}:$ver"
      return $LASTEXITCODE
    }
    [Console]::Error.WriteLine("scalasemantic-mcp: launching ${Org}:${Artifact}:$ver via coursier")
    & cs launch "${Org}:${Artifact}:$ver" -M $Main -- @Rest
    return $LASTEXITCODE
  }

  # --- path 2: fat jar from GitHub Releases ------------------------------------------------------
  $Cached = Get-NewestCached

  if (-not $Prefetch -and -not $env:SCALASEMANTIC_VERSION -and $Cached) {
    # Cached jar present and not pinned: serve it NOW (zero download latency) and fork a detached
    # updater that pulls the latest release for the next launch. The child runs hidden so it does not
    # hold the JSON-RPC stdout stream.
    $Jar = $Cached
    try {
      Start-Process -FilePath 'powershell' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Self, 'serve', '--bg-fetch') `
        -WindowStyle Hidden | Out-Null
    } catch { } # best-effort updater; serving the cached jar is what matters
  } else {
    # No cache (must block once), or pinned (fetch exactly that version), or prefetch (warm fully).
    $Tag = Resolve-Tag
    if ($Tag) { [void](Get-Release $Tag) }
    $Jar = if ($Tag) { Join-Path $Cache "scalasemantic-mcp-$Tag.jar" } else { $null }
    if (-not $Jar -or -not (Test-Path $Jar)) {
      $Jar = Get-NewestCached
      if (-not $Jar) { throw "scalasemantic-mcp: cannot resolve a release and no cached jar found" }
      [Console]::Error.WriteLine("scalasemantic-mcp: offline — using cached $(Split-Path $Jar -Leaf)")
    }
  }

  if ($Prefetch) {
    [Console]::Error.WriteLine("scalasemantic-mcp: prefetched $(Split-Path $Jar -Leaf)")
    return 0
  }

  & java -jar $Jar @Rest
  return $LASTEXITCODE
}

# ----------------------------------------------------------------------------------------------
# setup: idempotently configure a project + its MCP clients to launch this script via `serve`.
# ----------------------------------------------------------------------------------------------

$AllClients = @('claude', 'codex', 'gemini', 'cline', 'roo', 'continue', 'antigravity')

function Get-TargetFor([string]$Client) {
  switch ($Client.Trim().ToLowerInvariant()) {
    { $_ -in @('codex', 'openai', 'openai-codex') } { return @{ RelPath = '.codex/config.toml'; Fmt = 'toml' } }
    { $_ -in @('claude', 'claude-code', 'anthropic') } { return @{ RelPath = '.mcp.json'; Fmt = 'json' } }
    { $_ -in @('gemini', 'google', 'google-gemini', 'gemini-cli') } {
      return @{ RelPath = '.gemini/settings.json'; Fmt = 'json'; Extra = @{ timeout = 60000 } }
    }
    { $_ -in @('antigravity', 'antigravity-cli', 'agy') } { return @{ RelPath = '.agents/mcp_config.json'; Fmt = 'json' } }
    { $_ -eq 'cline' } { return @{ RelPath = '.cline/mcp.json'; Fmt = 'json'; Extra = @{ disabled = $false; autoApprove = @() } } }
    { $_ -in @('roo', 'roo-code') } {
      return @{ RelPath = '.roo/mcp.json'; Fmt = 'json'; Extra = @{ disabled = $false; alwaysAllow = @(); timeout = 60 } }
    }
    { $_ -in @('continue', 'continue-dev') } { return @{ RelPath = '.continue/config.yaml'; Fmt = 'yaml' } }
    { $_ -in @('generic', 'generic-json', 'json', 'oss', 'open-source', 'free') } { return @{ RelPath = '.mcp.json'; Fmt = 'json' } }
    default { throw "unsupported client '$Client'; use claude, codex, gemini, cline, roo, continue, antigravity, generic-json, or all" }
  }
}

function Ensure-SemanticdbConfig([string]$Project) {
  $sbtFiles = Get-ChildItem -Path $Project -Filter '*.sbt' -File -ErrorAction SilentlyContinue
  if ($sbtFiles | Where-Object { (Get-Content $_.FullName -Raw) -match 'semanticdbEnabled' }) { return }
  if (-not $sbtFiles) {
    [Console]::Error.WriteLine("scalasemantic-mcp: no .sbt files found in $Project; enable SemanticDB in your build tool before using ScalaSemantic")
    return
  }
  $file = Join-Path $Project 'scala-semantic.sbt'
  if (-not (Test-Path $file)) {
    Set-Content -Path $file -NoNewline -Value "// Generated by ScalaSemantic MCP setup.`nsemanticdbEnabled := true`n"
    [Console]::Error.WriteLine("scalasemantic-mcp: created $file")
  }
}

function Ensure-SteerFile([string]$Project, [string]$Client) {
  $target = $null; $ref = $null
  switch ($Client.Trim().ToLowerInvariant()) {
    { $_ -in @('claude', 'claude-code', 'anthropic') } {
      $target = Join-Path $Project 'CLAUDE.md'; $ref = '[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)'
    }
    { $_ -in @('gemini', 'google', 'google-gemini', 'gemini-cli', 'antigravity', 'antigravity-cli', 'agy') } {
      $target = Join-Path $Project 'AGENTS.md'; $ref = '@SCALA_SEMANTIC_RULES.md'
    }
    { $_ -in @('codex', 'openai', 'openai-codex') } {
      $target = Join-Path $Project '.cursorrules'; $ref = '[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)'
    }
    { $_ -in @('cline', 'roo', 'roo-code') } {
      $target = Join-Path $Project '.clinerules'; $ref = '[SCALA_SEMANTIC_RULES.md](SCALA_SEMANTIC_RULES.md)'
    }
    { $_ -in @('continue', 'continue-dev') } {
      $target = Join-Path $Project '.continue/rules.txt'; $ref = 'SCALA_SEMANTIC_RULES.md'
    }
    default { return }
  }
  if (Test-Path $target) {
    $current = Get-Content $target -Raw
    if ($current -match 'SCALA_CODE_RULES\.md') {
      Set-Content -Path $target -NoNewline -Value ($current -replace 'SCALA_CODE_RULES\.md', 'SCALA_SEMANTIC_RULES.md')
      [Console]::Error.WriteLine("scalasemantic-mcp: updated $target")
    } elseif ($current -notmatch 'SCALA_SEMANTIC_RULES\.md') {
      Add-Content -Path $target -Value "`n## Scala Code Rules`nPlease follow the rules in $ref.`n"
      [Console]::Error.WriteLine("scalasemantic-mcp: updated $target")
    }
  } else {
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    $content = if ((Split-Path $target -Leaf) -eq 'AGENTS.md') {
      "# AGENTS.md instructions`n`n<INSTRUCTIONS>`n$ref`n</INSTRUCTIONS>`n"
    } else {
      "# Project Rules`n`nPlease follow the rules in $ref for working with Scala code.`n"
    }
    Set-Content -Path $target -NoNewline -Value $content
    [Console]::Error.WriteLine("scalasemantic-mcp: created $target")
  }
}

function Ensure-Rules([string]$Project, [string]$Client) {
  $rules = Join-Path $Project 'SCALA_SEMANTIC_RULES.md'
  if (-not (Test-Path $rules)) {
    Set-Content -Path $rules -NoNewline -Value @'
# Scala Semantic Rules

For Scala source questions, use ScalaSemantic MCP tools before shell text tools. Preferably compile code before usage, then more ScalaSemantic functions can be used with better results.

Do not use `cat`, `sed`, `rg`, or similar tools to inspect `.scala` files for symbol, type, signature, hierarchy, implicit, reference, or call-path questions when ScalaSemantic tools are available.

Use shell for builds, tests, git, config, docs, scripts, and non-Scala text work.

In Claude Code this rule is enforced, not merely advised: the `.claude/hooks/scala-semantic-guard.sh` PreToolUse hook denies Read/Grep/Glob and shell text tools that target `.scala` files. If the semantic tools genuinely cannot answer, re-run the command through Bash with a trailing `# semantic-fallback: <reason>` marker -- allowed, and logged to `.claude/semantic-fallback.log`.
'@
    [Console]::Error.WriteLine("scalasemantic-mcp: created $rules")
  }
  $clients = if ($Client.Trim().ToLowerInvariant() -eq 'all') { $AllClients } else { @($Client) }
  foreach ($c in $clients) { Ensure-SteerFile $Project $c }
}

# Safe array slice: PowerShell's `$a[$from..$to]` silently walks BACKWARDS when $from > $to (e.g.
# empty-args count-1 == -1, or a match at the very first/last line), which corrupts the merge. This
# always returns the ascending sub-range, or @() when it would be empty/invalid.
function Slice-Array([array]$Items, [int]$From, [int]$To) {
  if ($null -eq $Items -or $Items.Count -eq 0 -or $From -gt $To -or $From -ge $Items.Count -or $To -lt 0) { return @() }
  $From = [Math]::Max($From, 0)
  $To = [Math]::Min($To, $Items.Count - 1)
  return @($Items[$From..$To])
}

# Renders/merges the single "scala-semantic" entry into a JSON MCP config, preserving everything
# else in the file untouched.
function Merge-Json([string]$Existing, [string]$Server, [string[]]$Argv, [hashtable]$Extra) {
  $doc = $null
  if ($Existing -and $Existing.Trim()) {
    try { $doc = $Existing | ConvertFrom-Json } catch { $doc = $null }
  }
  if (-not $doc) { $doc = [PSCustomObject]@{} }
  if (-not ($doc.PSObject.Properties.Name -contains 'mcpServers')) {
    $doc | Add-Member -NotePropertyName mcpServers -NotePropertyValue ([PSCustomObject]@{})
  }
  $entry = [ordered]@{ command = $Argv[0]; args = Slice-Array $Argv 1 ($Argv.Count - 1) }
  foreach ($k in $Extra.Keys) { $entry[$k] = $Extra[$k] }
  if ($doc.mcpServers.PSObject.Properties.Name -contains $Server) {
    $doc.mcpServers.PSObject.Properties.Remove($Server)
  }
  $doc.mcpServers | Add-Member -NotePropertyName $Server -NotePropertyValue ([PSCustomObject]$entry)
  return ($doc | ConvertTo-Json -Depth 10) + "`n"
}

function Render-CodexToml([string]$Server, [string[]]$Argv) {
  $rest = Slice-Array $Argv 1 ($Argv.Count - 1)
  $argsToml = '[' + (($rest | ForEach-Object { $_ | ConvertTo-Json -Compress }) -join ', ') + ']'
  return @"
[mcp_servers.$Server]
command = $($Argv[0] | ConvertTo-Json -Compress)
args = $argsToml
startup_timeout_sec = 60
tool_timeout_sec = 60
"@
}

function Merge-Toml([string]$Existing, [string]$Server, [string[]]$Argv) {
  $fresh = Render-CodexToml $Server $Argv
  if (-not ($Existing -and $Existing.Trim())) { return $fresh + "`n" }
  $header = "[mcp_servers.$Server]"
  $lines = $Existing -split "`n"
  $idx = -1
  for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].Trim() -eq $header) { $idx = $i; break } }
  if ($idx -lt 0) {
    $sep = if ($Existing.EndsWith("`n")) { '' } else { "`n" }
    return $Existing + $sep + "`n" + $fresh + "`n"
  }
  $end = $lines.Count
  for ($i = $idx + 1; $i -lt $lines.Count; $i++) { if ($lines[$i].Trim().StartsWith('[')) { $end = $i; break } }
  $out = ((Slice-Array $lines 0 ($idx - 1)) + @($fresh -split "`n") + (Slice-Array $lines $end ($lines.Count - 1))) -join "`n"
  return $out.TrimEnd("`n") + "`n"
}

function Get-ContinueItem([string]$Server, [string[]]$Argv) {
  $args = ''
  $rest = Slice-Array $Argv 1 ($Argv.Count - 1)
  if ($rest.Count -gt 0) {
    $args = "`n    args:" + (($rest | ForEach-Object { "`n      - $($_ | ConvertTo-Json -Compress)" }) -join '')
  }
  return "  - name: $($Server | ConvertTo-Json -Compress)`n    command: $($Argv[0] | ConvertTo-Json -Compress)$args`n    connectionTimeout: 60000"
}

function Merge-Yaml([string]$Existing, [string]$Server, [string[]]$Argv) {
  $item = Get-ContinueItem $Server $Argv
  $fresh = "name: ScalaSemantic MCP`nversion: 1.0.0`nschema: v1`nmcpServers:`n$item`n"
  if (-not ($Existing -and $Existing.Trim())) { return $fresh }
  $lines = $Existing -split "`n"
  $msIdx = -1
  for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].Trim() -eq 'mcpServers:') { $msIdx = $i; break } }
  if ($msIdx -lt 0) {
    $sep = if ($Existing.EndsWith("`n")) { '' } else { "`n" }
    return $Existing + $sep + "mcpServers:`n" + $item + "`n"
  }
  $blockEnd = $lines.Count
  for ($i = $msIdx + 1; $i -lt $lines.Count; $i++) {
    if ($lines[$i].Trim() -and -not $lines[$i].StartsWith(' ')) { $blockEnd = $i; break }
  }
  $nameLine = "- name: $($Server | ConvertTo-Json -Compress)"
  $itemIdx = -1
  for ($i = $msIdx + 1; $i -lt $blockEnd; $i++) { if ($lines[$i].Trim() -eq $nameLine) { $itemIdx = $i; break } }
  if ($itemIdx -lt 0) {
    $out = ((Slice-Array $lines 0 $msIdx) + @($item -split "`n") + (Slice-Array $lines ($msIdx + 1) ($lines.Count - 1))) -join "`n"
  } else {
    $indent = $lines[$itemIdx].Length - $lines[$itemIdx].TrimStart(' ').Length
    $e = $blockEnd
    for ($i = $itemIdx + 1; $i -lt $blockEnd; $i++) {
      $li = $lines[$i].Length - $lines[$i].TrimStart(' ').Length
      if ($li -eq $indent -and $lines[$i].Trim().StartsWith('- ')) { $e = $i; break }
    }
    $before = Slice-Array $lines 0 ($itemIdx - 1)
    $after = Slice-Array $lines $e ($lines.Count - 1)
    $out = ($before + @($item -split "`n") + $after) -join "`n"
  }
  return $out.TrimEnd("`n") + "`n"
}

function Write-ClientConfigs([string]$Project, [string]$Client, [string[]]$CommandArgv) {
  $clients = if ($Client.Trim().ToLowerInvariant() -eq 'all') { $AllClients } else { @($Client) }
  foreach ($c in $clients) {
    $t = Get-TargetFor $c
    $out = Join-Path $Project $t.RelPath
    New-Item -ItemType Directory -Force -Path (Split-Path $out -Parent) | Out-Null
    $existing = if (Test-Path $out) { Get-Content $out -Raw } else { '' }
    $extra = if ($t.Extra) { $t.Extra } else { @{} }
    $merged = switch ($t.Fmt) {
      'json' { Merge-Json $existing 'scala-semantic' $CommandArgv $extra }
      'toml' { Merge-Toml $existing 'scala-semantic' $CommandArgv }
      'yaml' { Merge-Yaml $existing 'scala-semantic' $CommandArgv }
    }
    Set-Content -Path $out -NoNewline -Value $merged
    [Console]::Error.WriteLine("scalasemantic-mcp: wrote $out")
  }
}

# --- Claude Code guard hook ------------------------------------------------------------------
# Mirrors launcher/src/main/scala/com/github/mercurievv/scalasemantic/LauncherGuardHook.scala --
# keep the script body and the settings entry byte-identical with the jar-side implementation.
$GuardHookRelPath = '.claude/hooks/scala-semantic-guard.sh'
$GuardScript = @'
#!/bin/sh
# Generated by ScalaSemantic MCP setup -- do not edit; re-run `scalasemantic-mcp setup`
# to regenerate, or `scalasemantic-mcp setup --no-guard` to stop installing it (then drop
# the PreToolUse entry from .claude/settings.json).
#
# Claude Code PreToolUse hook, two jobs:
#   READS  -- text-scraping tools on .scala sources are denied, so symbol questions go to
#             the ScalaSemantic MCP tools, which answer from compiler facts at a fraction
#             of the tokens and without missing renames/implicits/inferred uses.
#   EDITS  -- writing a .scala source is allowed but reminds the agent to edit the
#             annotated buffer instead, so it edits with the compiler's inferred types and
#             implicits in view. `setup --strict-edits` turns that reminder into a denial
#             that the `# semantic-fallback:` marker cannot bypass.
#   BUFFERS -- an annotated_source READ is rewritten (PreToolUse updatedInput) to
#             format=compilable + sentinel=true, so what the agent gets back is an
#             editable buffer rather than a read-only view.
#
# Exit codes: 0 = allow (stdout is fed back to the agent as context), 2 = deny (stderr is).

set -u
strict_edits=0

root="${CLAUDE_PROJECT_DIR:-$PWD}"
payload=$(cat)

# --- no JSON reader: fail open ---------------------------------------------------------
if command -v jq >/dev/null 2>&1; then
  reader=jq
elif command -v python3 >/dev/null 2>&1; then
  reader=python3
else
  exit 0
fi

# tool name, then the tool_input fields that can name a Scala target, one per line.
if [ "$reader" = jq ]; then
  fields=$(printf '%s' "$payload" | jq -r '
    [ (.tool_name // ""),
      (.tool_input.file_path // ""),
      (.tool_input.glob // ""),
      (.tool_input.path // ""),
      (.tool_input.type // ""),
      (.tool_input.command // "") ]
    | .[] | tostring | gsub("\n"; " ")' 2>/dev/null) || exit 0
else
  fields=$(printf '%s' "$payload" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
i = d.get("tool_input") or {}
keys = ["file_path", "glob", "path", "type", "command"]
out = [d.get("tool_name", "")] + [i.get(k, "") for k in keys]
print("\n".join(str(x).replace("\n", " ") for x in out))
' 2>/dev/null) || exit 0
fi

[ -n "$fields" ] || exit 0
tool=$(printf '%s\n' "$fields" | sed -n 1p)
file_path=$(printf '%s\n' "$fields" | sed -n 2p)
glob=$(printf '%s\n' "$fields" | sed -n 3p)
path=$(printf '%s\n' "$fields" | sed -n 4p)
ftype=$(printf '%s\n' "$fields" | sed -n 5p)
command_line=$(printf '%s\n' "$fields" | sed -n 6p)

# --- upgrade a plain annotated_source read into an editable buffer ---------------------
# A read the agent asked for as `format=plain` (or with the default gutter view, or without
# `sentinel`) cannot be edited and written back: the gutter is not source, and notes that
# are not sentinel-delimited cannot be stripped. PreToolUse `updatedInput` rewrites the
# call in place, so the agent gets a buffer whatever it asked for, without a round trip.
# Writes (`write` present) are passed through untouched.
if [ "$tool" = mcp__scala-semantic__annotated_source ] && [ "$reader" = jq ]; then
  upgraded=$(printf '%s' "$payload" | jq -c '
    if (.tool_input | type) != "object" then empty
    elif (.tool_input | has("write")) then empty
    elif (.tool_input.format == "compilable" and .tool_input.sentinel == true) then empty
    else { hookSpecificOutput: {
             hookEventName: "PreToolUse",
             permissionDecision: "allow",
             permissionDecisionReason:
               "ScalaSemantic guard: upgraded this read to an editable annotated buffer (format=compilable, sentinel=true) so it can be edited and written back through annotated_source.",
             updatedInput: (.tool_input + { format: "compilable", sentinel: true }) } }
    end' 2>/dev/null)
  if [ -n "${upgraded:-}" ]; then
    printf '%s\n' "$upgraded"
  fi
  exit 0
fi

# --- explicit human/agent override -----------------------------------------------------
# `rg foo *.scala   # semantic-fallback: <reason>` is allowed for READS, and logged so the
# override stays auditable instead of silent. Whether it applies is decided AFTER the call
# is classified, because it deliberately does not cover writes: a marker appended to
# `sed -i ... A.scala` would otherwise talk its way straight past --strict-edits, which is
# the one thing strict mode exists to prevent.
fallback=0
case "$command_line" in
  *semantic-fallback:*) fallback=1 ;;
esac

# --- does this call target Scala sources, and is it a read or a write? -----------------
# mode: "" = not our business, "read" = text-scraping a Scala source, "write" = editing one.
mode=
case "$tool" in
  Read)
    case "$file_path" in
      *.scala | *.sc) mode=read ;;
    esac
    ;;
  Grep | Glob)
    # Only when the call itself names Scala: an unscoped repo-wide search may legitimately
    # be after comments, config or non-Scala files.
    case "$glob$path$ftype" in
      *scala*) mode=read ;;
    esac
    ;;
  Edit | Write | MultiEdit | NotebookEdit)
    case "$file_path" in
      *.scala | *.sc) mode=write ;;
    esac
    ;;
  Bash)
    # `.scala` must end a path, not merely appear: `mill.scalalib`, `scalafmt` and
    # `.scala-build` are not Scala sources, and blocking them blocks the build itself.
    if printf '%s' "$command_line" | grep -Eq '\.(scala|sc)([^[:alnum:]_-]|$)'; then
      if printf '%s' "$command_line" | grep -Eq \
        '(^|[|;&(`]|[[:space:]])(grep|rg|ag|ack|cat|sed|awk|head|tail|less|more|nl)([[:space:]]|$)'
      then
        mode=read
      fi
      # Handing the file to a runner is executing it, not reading it -- and the pipeline
      # that filters its OUTPUT (`scala-cli foo.sc | grep ...`) is not a text search either.
      if printf '%s' "$command_line" | grep -Eq \
        '(^|[|;&(`/]|[[:space:]])(scala-cli|scala|scalac|amm|mill|sbt|java)([[:space:]]|$)'
      then
        mode=
      fi
      # A redirect or in-place edit whose TARGET is the Scala file is a write, not a read --
      # and it outranks a reader that appears on the same line (`cat > A.scala`).
      if printf '%s' "$command_line" | grep -Eq \
        '(>>?|tee)[[:space:]]*"?[^[:space:]"]*\.(scala|sc)([[:space:]"]|$)|sed[[:space:]]+-i[^|;&]*\.(scala|sc)([[:space:]]|$)'
      then
        mode=write
      fi
    fi
    ;;
esac
[ -n "$mode" ] || exit 0

# The override, now that we know what kind of call this is: it releases a read, and is
# logged either way so an attempt to use it on a write stays visible.
if [ "$fallback" = 1 ]; then
  printf '%s\t%s\t%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$mode" "$command_line" \
    >>"$root/.claude/semantic-fallback.log" 2>/dev/null
  if [ "$mode" = read ]; then exit 0; fi
fi

# --- fail open when the semantic answer is not actually available ----------------------
# No MCP server wired into this project: nothing better to route the agent to.
for cfg in "$root/.mcp.json" "$root/.claude/settings.json" "$root/.claude/settings.local.json"; do
  [ -f "$cfg" ] && grep -q 'scala-semantic' "$cfg" 2>/dev/null && configured=1
done
[ "${configured:-0}" = 1 ] || exit 0

# No SemanticDB emitted yet (never compiled, or a non-Scala project): the MCP tools would
# return an empty index, so text search is the only thing that can work.
# The index lives INSIDE the build's output dir for every mainstream build tool -- Mill:
# out/<mod>/semanticDbData*.dest/classes/META-INF/semanticdb, sbt: target/scala-3.*/**/
# META-INF/semanticdb -- so `out`/`target`/`.scala-build` must NOT be pruned here, or the
# probe finds nothing and the guard silently fails open on every real project. Matching the
# distinctive META-INF/semanticdb path keeps the walk cheap without them.
index=$(find "$root" \
  \( -name .git -o -name node_modules -o -name .worktrees -o -name website \) -prune -o \
  -path '*/META-INF/semanticdb/*.semanticdb' -print 2>/dev/null | head -n 1)
[ -n "$index" ] || exit 0

# --- editing a Scala source ------------------------------------------------------------
# Not a denial by default: a three-line change through Edit is cheaper than a whole-file
# roundtrip. The reminder exists because the annotated buffer is what makes the edit
# compiler-aware, and nothing else in the session mentions it at the moment of the edit.
if [ "$mode" = write ]; then
  if [ "$strict_edits" = 1 ]; then
    cat >&2 <<'MSG'
BLOCKED by ScalaSemantic guard (--strict-edits): edit .scala sources through the MCP write
path, so the edit is made against the compiler's view of the file:
  1. annotated_source(uri, format="compilable", sentinel=true)
     -> the source with inferred types, implicit args and conversions inline as
        /*SEM:...:SEM*/ blocks (no line-number gutter), plus its sha256
  2. edit that buffer, leaving every SEM block exactly where it is -- they are stripped
     for you, and taking them out by hand edits lines your change does not concern
  3. annotated_source(uri, write=<edited text>, baseHash=<that sha256>)
     -> every SEM block is stripped before the file is saved
Both arguments matter: sentinel=true makes the notes machine-strippable, format=compilable
drops the read-only gutter. Any other read is a view, not a buffer -- writing it back would
persist annotations into the source, and is refused.
A `# semantic-fallback:` marker does NOT exempt a write: under --strict-edits this is the
only way to change a .scala source.
Re-run `scalasemantic-mcp setup` without --strict-edits to make this a reminder instead.
MSG
    exit 2
  fi
  cat <<'MSG'
ScalaSemantic: editing a Scala source. For an annotation-aware edit, work on the annotated
buffer instead of the raw text:
  1. annotated_source(uri, format="compilable", sentinel=true)
     -> inferred types, implicit args and conversions inline as /*SEM:...:SEM*/ blocks
        (no line-number gutter), plus its sha256
  2. edit that buffer, leaving every SEM block exactly where it is -- they are stripped
     for you, and taking them out by hand edits lines your change does not concern
  3. annotated_source(uri, write=<edited text>, baseHash=<that sha256>)
     -> SEM blocks are stripped before the file is saved
Both arguments matter: sentinel=true makes the notes machine-strippable, format=compilable
drops the read-only gutter. Any other read is a view, not a buffer -- writing it back would
persist annotations into the source, and is refused.
A small mechanical edit can stay with this tool -- this is a reminder, not a refusal.
MSG
  exit 0
fi

# --- deny ------------------------------------------------------------------------------
cat >&2 <<'MSG'
BLOCKED by ScalaSemantic guard: text tools are not allowed on .scala sources here.
Text search misses renames, re-exports, implicits and inferred uses, and over-matches
comments and same-named identifiers.
To READ a Scala file, this is the tool:
  annotated_source(uri)
     -> the whole source, plus the inferred types, implicit arguments and conversions the
        compiler resolved, inline. `cat` shows none of that.
To EDIT one, read it as a buffer and write that buffer back:
  annotated_source(uri, format="compilable", sentinel=true)   -> text + its sha256
  annotated_source(uri, write=<edited text>, baseHash=<that sha256>)
     -> the /*SEM:...:SEM*/ blocks are stripped before the file is saved
Leave those blocks where they are in the text you send: the server removes them, and
removing them yourself edits lines your change does not concern.
For anything else, pick the tool that fits the question:
  symbols / references / types  -> find_symbol, find_usages, type_at_position
  hierarchy / members / givens  -> class_hierarchy, members, resolve_implicits
  signatures / overloads        -> method_signature, find_overloads
  file or project shape         -> document_outline, structure, symbol_source
  literals, comments, TODOs     -> search_text
Stale or missing index: run the project's compile task, then refresh_workspace — or, if you
cannot run the build yourself (e.g. this session cannot shell out), call refresh_workspace
with compile=true and it will detect and run the build itself.
If the semantic tools genuinely cannot answer this, re-run the READ through Bash with a
trailing `# semantic-fallback: <reason>` marker (allowed, and logged). It releases reads
only -- a write still has to go through annotated_source.
MSG
exit 2
'@

# Splices the guard entry into .claude/settings.json, preserving whatever else is configured.
function Merge-GuardSettings([string]$Existing) {
  $doc = $null
  if ($Existing -and $Existing.Trim()) {
    try { $doc = $Existing | ConvertFrom-Json } catch { $doc = $null }
  }
  if (-not $doc) { $doc = [PSCustomObject]@{} }
  if (-not ($doc.PSObject.Properties.Name -contains 'hooks')) {
    $doc | Add-Member -NotePropertyName hooks -NotePropertyValue ([PSCustomObject]@{})
  }
  $entry = [PSCustomObject]@{
    matcher = 'Read|Grep|Glob|Bash|Edit|Write|MultiEdit'
    hooks   = @([PSCustomObject]@{
      type    = 'command'
      command = '"$CLAUDE_PROJECT_DIR"/.claude/hooks/scala-semantic-guard.sh'
    })
  }
  $existingPre = @()
  if ($doc.hooks.PSObject.Properties.Name -contains 'PreToolUse') {
    $existingPre = @($doc.hooks.PreToolUse)
    $doc.hooks.PSObject.Properties.Remove('PreToolUse')
  }
  $doc.hooks | Add-Member -NotePropertyName PreToolUse -NotePropertyValue (@($entry) + $existingPre)
  return ($doc | ConvertTo-Json -Depth 10) + "`n"
}

function Install-GuardHook([string]$Project, [string]$Client, [bool]$StrictEdits = $false) {
  $normalized = $Client.Trim().ToLowerInvariant().Replace('_', '-')
  if ($normalized -notin @('all', 'claude', 'claude-code', 'anthropic')) { return }

  $hook = Join-Path $Project $GuardHookRelPath
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $hook) | Out-Null
  # LF endings on purpose: `sh` chokes on a CRLF shebang line, and Git Bash/WSL run this file.
  $body = $GuardScript.Replace("`r`n", "`n")
  # The only difference between the two hook variants: reminder (0) vs denial (1) on edits.
  if ($StrictEdits) { $body = $body.Replace("strict_edits=0", "strict_edits=1") }
  if (-not $body.EndsWith("`n")) { $body += "`n" }
  $existing = if (Test-Path $hook) { [System.IO.File]::ReadAllText($hook) } else { $null }
  if ($existing -ne $body) {
    [System.IO.File]::WriteAllText($hook, $body)
    $verb = if ($null -eq $existing) { 'created' } else { 'updated' }
    [Console]::Error.WriteLine("scalasemantic-mcp: $verb $hook")
  }

  $settings = Join-Path $Project '.claude/settings.json'
  $current = if (Test-Path $settings) { [System.IO.File]::ReadAllText($settings) } else { '' }
  if ($current -match 'scala-semantic-guard') { return }
  [System.IO.File]::WriteAllText($settings, (Merge-GuardSettings $current))
  [Console]::Error.WriteLine("scalasemantic-mcp: registered guard hook in $settings")
}

function Setup-Main {
  param([string[]]$Rest = @())

  $Project = (Get-Location).Path
  $Client = 'all'
  $Command = @('powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Self)
  $SkipSemanticdb = $false
  $Guard = $true
  $StrictEdits = $false

  $i = 0
  while ($i -lt $Rest.Count) {
    switch ($Rest[$i]) {
      { $_ -in @('--project', '--root', '-Project') } {
        New-Item -ItemType Directory -Force -Path $Rest[$i + 1] | Out-Null
        $Project = (Resolve-Path $Rest[$i + 1]).Path
        $i += 2
      }
      { $_ -in @('--client', '-c', '-ClientName') } { $Client = $Rest[$i + 1]; $i += 2 }
      { $_ -in @('--command', '-Command') } { $Command = @($Rest[$i + 1]); $i += 2 }
      { $_ -in @('--skip-semanticdb-config', '-SkipSemanticdbConfig') } { $SkipSemanticdb = $true; $i += 1 }
      { $_ -in @('--no-guard', '-NoGuard') } { $Guard = $false; $i += 1 }
      { $_ -in @('--guard', '-Guard') } { $Guard = $true; $i += 1 }
      { $_ -in @('--strict-edits', '-StrictEdits') } { $StrictEdits = $true; $i += 1 }
      { $_ -in @('--no-strict-edits', '-NoStrictEdits') } { $StrictEdits = $false; $i += 1 }
      { $_ -in @('--help', '-h') } { Show-Usage; return }
      default { throw "scalasemantic-mcp: unknown setup argument: $($Rest[$i])" }
    }
  }

  if (-not $SkipSemanticdb) { Ensure-SemanticdbConfig $Project }
  Ensure-Rules $Project $Client

  [Console]::Error.WriteLine("scalasemantic-mcp: prefetching the server jar ...")
  try { Serve-Main -Rest @('-Prefetch', $Project) | Out-Null }
  catch { [Console]::Error.WriteLine("scalasemantic-mcp: prefetch skipped (will download on first serve)") }

  $argv = @($Command) + @('serve', '.')
  Write-ClientConfigs $Project $Client $argv
  if ($Guard) { Install-GuardHook $Project $Client $StrictEdits }
}

function Show-Usage {
  [Console]::Error.WriteLine(@"
Usage:
  scalasemantic-mcp.ps1 setup [-ClientName all|claude|codex|gemini|cline|roo|continue|antigravity] [-Project DIR] [-NoGuard] [-StrictEdits]
  scalasemantic-mcp.ps1 serve <semanticdb-root> [classpath-file] [--log] [--log-output]

For Claude Code, setup also installs a PreToolUse guard hook that denies text tools on .scala
sources (.claude/hooks/scala-semantic-guard.sh); pass -NoGuard to skip it. Editing a Scala
source is only reminded about; pass -StrictEdits to deny that too, so edits go through
annotated_source's write mode.

setup writes MCP client config that launches this same script:
  command = powershell
  args    = [-NoProfile, -ExecutionPolicy, Bypass, -File, $Self, serve, .]
"@)
}

$AllArgs = @($args)
$FirstArg = if ($AllArgs.Count -gt 0) { $AllArgs[0] } else { '' }
switch ($FirstArg) {
  { $_ -in @('setup', 'configure', 'install') } { Setup-Main -Rest (Slice-Array $AllArgs 1 ($AllArgs.Count - 1)); exit 0 }
  { $_ -in @('serve', 'run') } { exit (Serve-Main -Rest (Slice-Array $AllArgs 1 ($AllArgs.Count - 1))) }
  { $_ -in @('--help', '-h', 'help') } { Show-Usage; exit 0 }
  default { exit (Serve-Main -Rest $AllArgs) }
}
