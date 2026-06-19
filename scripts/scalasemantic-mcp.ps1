# Auto-download + run the latest ScalaSemantic MCP server (Windows / PowerShell).
#
# Resolves the latest GitHub Release, downloads its fat jar once (cached by version), then runs it
# with `java -jar`. Download progress is suppressed so stdout carries only the JSON-RPC stream.
#
# Usage:   powershell -ExecutionPolicy Bypass -File scalasemantic-mcp.ps1 <semanticdb-root>
# Requires: java on PATH. No coursier/sbt needed.
#
# Pin a version instead of "latest" with:  $env:SCALASEMANTIC_VERSION = "v0.1.4"
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # keep Invoke-WebRequest's progress bar off the streams

$Repo  = 'MercurieVV/ScalaSemantic'
$Cache = Join-Path $env:LOCALAPPDATA 'scalasemantic-mcp'
New-Item -ItemType Directory -Force -Path $Cache | Out-Null

# --- resolve version: explicit pin, else the latest release's tag_name ---------------------------
$Tag = $env:SCALASEMANTIC_VERSION
if (-not $Tag) {
  try {
    $Tag = (Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest").tag_name
  } catch { $Tag = $null }
}

$Jar = if ($Tag) { Join-Path $Cache "scalasemantic-mcp-$Tag.jar" } else { $null }

# --- download if missing; fall back to newest cached jar when offline ----------------------------
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

$Root = if ($args.Count -ge 1) { $args[0] } else { '.' }
& java -jar $Jar $Root