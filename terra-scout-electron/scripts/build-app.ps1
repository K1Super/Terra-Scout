# 打包桌面安装包（release.md 8.2/8.4）：
# 1) 构建 React 前端（进入 asar）
# 2) 确保 resources/terrascout.jar 存在（无则提示先跑 build-jre.ps1 / mvn package）
# 3) electron-builder --win nsis 生成安装包
$ErrorActionPreference = 'Stop'

$electronDir = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $electronDir

Write-Host '==> 1/3 构建前端' -ForegroundColor Cyan
Push-Location $electronDir
npm run build
Pop-Location

$jar = Join-Path $electronDir 'resources\terrascout.jar'
if (-not (Test-Path $jar)) {
  Write-Host '未找到 resources/terrascout.jar，复制后端产物…' -ForegroundColor Yellow
  $src = Join-Path $root 'terra-scout-app\target\terra-scout-app-0.1.0-SNAPSHOT.jar'
  if (-not (Test-Path $src)) {
    Write-Error "缺少后端 jar：$src（请先在仓库根 mvn package -DskipTests，以及执行 build-jre.ps1 生成 JRE）"
  }
  New-Item -ItemType Directory -Force -Path (Join-Path $electronDir 'resources') | Out-Null
  Copy-Item $src $jar -Force
}

Write-Host '==> 2/3 校验 JRE' -ForegroundColor Cyan
$jre = Join-Path $electronDir 'resources\jre'
if (-not (Test-Path (Join-Path $jre 'bin\java.exe'))) {
  Write-Error "缺少裁剪 JRE：$jre（请先执行 scripts/build-jre.ps1）"
}

Write-Host '==> 3/3 electron-builder NSIS 打包' -ForegroundColor Cyan
Push-Location $electronDir
npm run package
Pop-Location

Write-Host '完成：见 terra-scout-electron/dist 下的安装包' -ForegroundColor Green