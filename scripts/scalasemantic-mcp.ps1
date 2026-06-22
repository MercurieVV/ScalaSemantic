# Auto-download + run the ScalaSemantic MCP server (Windows / PowerShell).
#
# Two paths, picked automatically:
#   1. if `cs` (coursier) is on PATH — resolve + cache the artifact from Maven Central and run it;
#   2. otherwise — download the fat jar from the latest GitHub Release once (cached by version) and
#      run it with `java -jar`.
# Download progress is suppressed so stdout carries only the JSON-RPC stream.
#
# Cold-start strategy (jar path): once ANY version is cached, a launch serves the newest cached jar
# IMMEDIATELY and forks a detached background updater that fetches the latest release for the NEXT
# launch. So the download never races the client's connect timeout after the first time. The very
# first launch (empty cache) still blocks on the download — run `--prefetch` once, or `sbt
# mcpClientConfig` (which prefetches for you), to warm the cache ahead of the first real connect.
#
# Usage:   powershell -ExecutionPolicy Bypass -File scalasemantic-mcp.ps1 [--prefetch] <semanticdb-root> [classpath]
#   --prefetch  Download + cache the artifact, then exit WITHOUT serving. Run this once before
#               adding the server to your MCP config so the first real connect hits a warm cache
#               instead of racing the client's connection timeout while an ~88 MB jar downloads.
#   All other arguments are forwarded verbatim to the server: arg 1 = SemanticDB root, optional
#   arg 2 = the compile classpath (a path-separated string or a file containing one) that enables
#   the presentation-compiler backend for live overlay of uncompiled buffers.
#   --log / --log-output  Forwarded to the server to turn on its (off-by-default) file log:
#               --log writes a startup line + one line per tool call; --log-output additionally
#               logs each JSON-RPC response sent to the LLM. (Env equivalents: SCALASEMANTIC_LOG,
#               SCALASEMANTIC_LOG_OUTPUT; log file path via SCALASEMANTIC_LOG_FILE.)
# Requires: java on PATH (and optionally coursier). No sbt needed.
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

$Rest = @($args)

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
  exit 0
}

# --prefetch: warm the cache and exit, never serve (decouples the download from the first connect).
$Prefetch = $false
if ($Rest.Count -ge 1 -and $Rest[0] -eq '--prefetch') {
  $Prefetch = $true
  $Rest = @($Rest[1..($Rest.Count - 1)])
}

# --- path 1: coursier (preferred) ----------------------------------------------------------------
if (Get-Command cs -ErrorAction SilentlyContinue) {
  $ver = if ($env:SCALASEMANTIC_VERSION) { $env:SCALASEMANTIC_VERSION -replace '^v', '' } else { 'latest.release' }
  if ($Prefetch) {
    [Console]::Error.WriteLine("scalasemantic-mcp: prefetching ${Org}:${Artifact}:$ver via coursier")
    & cs fetch "${Org}:${Artifact}:$ver"
    exit $LASTEXITCODE
  }
  [Console]::Error.WriteLine("scalasemantic-mcp: launching ${Org}:${Artifact}:$ver via coursier")
  & cs launch "${Org}:${Artifact}:$ver" -M $Main -- @Rest
  exit $LASTEXITCODE
}

# --- path 2: fat jar from GitHub Releases --------------------------------------------------------
$Cached = Get-NewestCached

if (-not $Prefetch -and -not $env:SCALASEMANTIC_VERSION -and $Cached) {
  # Cached jar present and not pinned: serve it NOW (zero download latency) and fork a detached
  # updater that pulls the latest release for the next launch. The child runs hidden so it does not
  # hold the JSON-RPC stdout stream.
  $Jar = $Cached
  try {
    Start-Process -FilePath 'powershell' `
      -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '--bg-fetch') `
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
  exit 0
}

& java -jar $Jar @Rest
exit $LASTEXITCODE