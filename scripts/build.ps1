# Terra Scout 一键生产构建：后端 jar → 裁剪 JRE → 前端构建 → NSIS 安装包。
# 参数：-SkipTests 后端打包阶段跳过测试（默认执行测试，保证进入产物的代码经过门禁）。
param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$electronDir = Join-Path $root 'terra-scout-electron'

Write-Host '==> 1/4 后端构建（含测试）' -ForegroundColor Cyan
Push-Location $root
mvn -q package $(if ($SkipTests) { '-DskipTests' } else { '' })
if ($LASTEXITCODE -ne 0) { Write-Error '后端构建失败' }
Pop-Location

Write-Host '==> 2/5 裁剪 JRE' -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'build-jre.ps1') -SkipMaven
if ($LASTEXITCODE -ne 0) { Write-Error 'JRE 裁剪失败' }

Write-Host '==> 3/5 同步后端 jar 到 electron/resources' -ForegroundColor Cyan
$jarSrc = Join-Path $root 'terra-scout-app\target\terra-scout-app-0.1.0-SNAPSHOT.jar'
$jarDst = Join-Path $electronDir 'resources\terrascout.jar'
if (-not (Test-Path $jarSrc)) { Write-Error "缺少后端 jar：$jarSrc（mvn package 失败？）" }
New-Item -ItemType Directory -Force -Path (Split-Path $jarDst) | Out-Null
Copy-Item $jarSrc $jarDst -Force

Write-Host '==> 4/5 前端构建' -ForegroundColor Cyan
Push-Location $electronDir
npm run build
if ($LASTEXITCODE -ne 0) { Write-Error '前端构建失败' }
Pop-Location

Write-Host '==> 5/5 electron-builder NSIS 打包' -ForegroundColor Cyan
Push-Location $electronDir
npm run package
if ($LASTEXITCODE -ne 0) { Write-Error '安装包打包失败' }
Pop-Location

Write-Host '完成：安装包见 terra-scout-electron/dist' -ForegroundColor Green