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

function Setup-Main {
  param([string[]]$Rest = @())

  $Project = (Get-Location).Path
  $Client = 'all'
  $Command = @('powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Self)
  $SkipSemanticdb = $false

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
}

function Show-Usage {
  [Console]::Error.WriteLine(@"
Usage:
  scalasemantic-mcp.ps1 setup [-ClientName all|claude|codex|gemini|cline|roo|continue|antigravity] [-Project DIR]
  scalasemantic-mcp.ps1 serve <semanticdb-root> [classpath-file] [--log] [--log-output]

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
