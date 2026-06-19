# Auto-download + run the ScalaSemantic MCP server (Windows / PowerShell).
#
# Two paths, picked automatically:
#   1. if `cs` (coursier) is on PATH — resolve + cache the artifact from Maven Central and run it;
#   2. otherwise — download the fat jar from the latest GitHub Release once (cached by version) and
#      run it with `java -jar`.
# Download progress is suppressed so stdout carries only the JSON-RPC stream.
#
# Usage:   powershell -ExecutionPolicy Bypass -File scalasemantic-mcp.ps1 <semanticdb-root> [classpath]
#   All arguments are forwarded verbatim to the server: arg 1 = SemanticDB root, optional arg 2 =
#   the compile classpath (a path-separated string or a file containing one) that enables the
#   presentation-compiler backend for live overlay of uncompiled buffers.
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
$Root     = if ($args.Count -ge 1) { $args[0] } else { '.' }

# --- path 1: coursier (preferred) ----------------------------------------------------------------
if (Get-Command cs -ErrorAction SilentlyContinue) {
  $ver = if ($env:SCALASEMANTIC_VERSION) { $env:SCALASEMANTIC_VERSION -replace '^v', '' } else { 'latest.release' }
  [Console]::Error.WriteLine("scalasemantic-mcp: launching ${Org}:${Artifact}:$ver via coursier")
  & cs launch "${Org}:${Artifact}:$ver" -M $Main -- @args
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

# download if missing; fall back to newest cached jar when offline
if ($Tag -and -not (Test-Path $Jar)) {
  $Url = "https://github.com/$Repo/releases/download/$Tag/scalasemantic-mcp.jar"
  [Console]::Error.WriteLine("scalasemantic-mcp: downloading $Tag ...")
  Invoke-WebRequest $Url -OutFile "$Jar.tmp"
  Move-Item -Force "$Jar.tmp" $Jar
}
if (-not $Jar -or -not (Test-Path $Jar)) {
  $Jar = Get-ChildItem -Path $Cache -Filter 'scalasemantic-mcp-*.jar' -ErrorAction SilentlyContinue |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
  if (-not $Jar) { throw "scalasemantic-mcp: cannot resolve a release and no cached jar found" }
  [Console]::Error.WriteLine("scalasemantic-mcp: offline — using cached $(Split-Path $Jar -Leaf)")
}

& java -jar $Jar @args
exit $LASTEXITCODE
