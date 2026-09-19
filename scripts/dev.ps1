# Terra Scout 本机联调启动器。
# 前置：仓库根已构建后端 jar（mvn package -DskipTests → terra-scout-app/target/…jar）。
# Bootstrap 防闪退：
#   1) 工作目录钉到 electron 工程根（npx electron . 依赖 cwd）
#   2) dist/main 或 dist/renderer 缺失/过期时自动 npm run build
#   3) 缺少 java 时给出明确报错而非静默退出
#   4) 控制台输出同步落盘 %USERPROFILE%\.terrascout\logs\electron-console.log
#      （独立文件：electron.log 归 Electron 主进程 logger 所有）
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$electronRoot = Join-Path $root 'terra-scout-electron'
$jar = Join-Path $root 'terra-scout-app\target\terra-scout-app-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) {
  Write-Host "[dev] backend jar not found: $jar" -ForegroundColor Yellow
  Write-Host '[dev] build it first at repo root: mvn package -DskipTests' -ForegroundColor Yellow
  exit 1
}

Push-Location $electronRoot
try {
  # dist 守卫：package.json main 指向 dist/main/index.js，窗口加载 dist/renderer/index.html；
  # missing/stale dist 会让 electron 瞬间退出。
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