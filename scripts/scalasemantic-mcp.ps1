# Auto-download + run the ScalaSemantic MCP server (Windows / PowerShell).
#
# Two paths, picked automatically:
#   1. if `cs` (coursier) is on PATH — resolve + cache the artifact from Maven Central and run it;
#   2. otherwise — download the fat jar from the latest GitHub Release once (cached by version) and
#      run it with `java -jar`.
# Download progress is suppressed so stdout carries only the JSON-RPC stream.
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
# Pin a version instead of "latest" with:  $env:SCALASEMANTIC_VERSION = "v0.1.4"
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # keep Invoke-WebRequest's progress bar off the streams

$Repo     = 'MercurieVV/ScalaSemantic'
$Org      = 'io.github.mercurievv'
$Artifact = 'scalasemantic-mcp_3'
$Main     = 'com.github.mercurievv.scalasemantic.mcpServer'
$Cache    = Join-Path $env:LOCALAPPDATA 'scalasemantic-mcp'

# --prefetch: warm the cache and exit, never serve (decouples the download from the first connect).
$Prefetch = $false
$Rest = @($args)
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
New-Item -ItemType Directory -Force -Path $Cache | Out-Null

# resolve version: explicit pin, else the latest release's tag_name
$Tag = $env:SCALASEMANTIC_VERSION
if (-not $Tag) {
  try { $Tag = (Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest").tag_name } catch { $Tag = $null }
}

$Jar = if ($Tag) { Join-Path $Cache "scalasemantic-mcp-$Tag.jar" } else { $null }

# download if missing; download is resumable + atomic (rename only on a verified-complete .tmp).
if ($Tag -and -not (Test-Path $Jar)) {
  $Url = "https://github.com/$Repo/releases/download/$Tag/scalasemantic-mcp.jar"
  $Tmp = "$Jar.tmp"
  [Console]::Error.WriteLine("scalasemantic-mcp: downloading $Tag ...")
  # Resume a partial .tmp from a previous (e.g. timed-out) attempt via a byte-range request, so a
  # slow first download completes across the client's auto-reconnect retries instead of restarting.
  $headers = @{}
  $have = 0
  if (Test-Path $Tmp) { $have = (Get-Item $Tmp).Length; if ($have -gt 0) { $headers['Range'] = "bytes=$have-" } }
  $ok = $false
  try {
    $resp = Invoke-WebRequest $Url -Headers $headers -OutFile $Tmp -PassThru
    $ok = $true
  } catch {
    # HTTP 416 (Range Not Satisfiable) means the .tmp is already complete — accept it as done.
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 416) { $ok = $true }
    else { [Console]::Error.WriteLine("scalasemantic-mcp: download incomplete ($($_.Exception.Message)); will resume on next launch") }
  }
  if ($ok) { Move-Item -Force $Tmp $Jar }
}
if (-not $Jar -or -not (Test-Path $Jar)) {
  $Jar = Get-ChildItem -Path $Cache -Filter 'scalasemantic-mcp-*.jar' -ErrorAction SilentlyContinue |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
  if (-not $Jar) { throw "scalasemantic-mcp: cannot resolve a release and no cached jar found" }
  [Console]::Error.WriteLine("scalasemantic-mcp: offline — using cached $(Split-Path $Jar -Leaf)")
}

if ($Prefetch) {
  [Console]::Error.WriteLine("scalasemantic-mcp: prefetched $(Split-Path $Jar -Leaf)")
  exit 0
}

& java -jar $Jar @Rest
exit $LASTEXITCODE