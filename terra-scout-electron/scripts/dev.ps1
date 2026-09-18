# Dev mode launcher for Terra Scout Electron app.
# - Prereq backend jar: run "mvn package -DskipTests" at repo root (app/target/terra-scout-app-0.1.0-SNAPSHOT.jar)
# - Bootstrap-safe (fixes instant-close "flash exit"):
#   1) working dir pin to electron project root (npx electron . depends on cwd)
#   2) auto npm run build when dist/main or dist/renderer is missing/stale
#   3) clear error when java is missing instead of silent electron exit
#   4) console output teed to %USERPROFILE%\.terrascout\logs\electron-console.log for diagnosis
#      (separate file: electron.log is owned by the Electron main process logger)
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 parses non-BOM UTF-8 as ANSI).
$ErrorActionPreference = 'Stop'

$electronRoot = Split-Path -Parent $PSScriptRoot                     # parent of scripts\ = electron project root
$repoRoot = Split-Path -Parent $electronRoot                          # repo root
$jar = Join-Path $repoRoot 'terra-scout-app\target\terra-scout-app-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) {
  Write-Host "[dev] backend jar not found: $jar" -ForegroundColor Yellow
  Write-Host '[dev] build it first at repo root: mvn package -DskipTests' -ForegroundColor Yellow
  exit 1
}

Push-Location $electronRoot
try {
  # dist guard: electron package.json main points to dist/main/index.js and the
  # window loads dist/renderer/index.html; missing/stale dist crashes electron instantly.
  $mainEntry = Join-Path $electronRoot 'dist\main\index.js'
  $rendererEntry = Join-Path $electronRoot 'dist\renderer\index.html'
  $mainSrc = Join-Path $electronRoot 'src\main\index.ts'
  $stale = (Test-Path $mainSrc) -and (Test-Path $mainEntry) -and
    ((Get-Item $mainEntry).LastWriteTime -lt (Get-Item $mainSrc).LastWriteTime)
  if (-not (Test-Path $mainEntry) -or -not (Test-Path $rendererEntry) -or $stale) {
    Write-Host '[dev] dist missing or stale, running npm run build ...' -ForegroundColor Cyan
    npm run build
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  }

  $env:TERRA_SCOUT_JAR = $jar                                     # absolute jar path
  if (-not $env:JAVA_BIN) {
    $env:JAVA_BIN = 'java'
  }
  if ($env:JAVA_BIN -eq 'java' -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host '[dev] java not found: install JDK 17 or set JAVA_BIN to java.exe' -ForegroundColor Red
    exit 1
  }

  $logDir = Join-Path $env:USERPROFILE '.terrascout\logs'
  New-Item -ItemType Directory -Force -Path $logDir | Out-Null
  $stdoutLog = Join-Path $logDir 'electron-console.log'

  Write-Host '[dev] starting Electron ...' -ForegroundColor Cyan
  Write-Host "[dev] log file: $stdoutLog" -ForegroundColor DarkGray
  npx electron . 2>&1 | Tee-Object -FilePath $stdoutLog -Append
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[dev] electron exited abnormally (exit $LASTEXITCODE), see $stdoutLog" -ForegroundColor Red
    exit $LASTEXITCODE
  }
} finally {
  Pop-Location
}
