# Terra Scout 全量质量门禁：后端测试 + 前端类型检查与单测。
# 任一阶段失败立即退出（非零码），保证门禁语义等价于 CI 单命令。
# 参数：-FrontendOnly 仅跑前端检查（后端不动）。
param([switch]$FrontendOnly)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$electronDir = Join-Path $root 'terra-scout-electron'

if (-not $FrontendOnly) {
  Write-Host '==> 1/2 后端 mvn test' -ForegroundColor Cyan
  Push-Location $root
  mvn test
  if ($LASTEXITCODE -ne 0) { Write-Error '后端测试失败' }
  Pop-Location
}

Write-Host '==> 2/2 前端 typecheck + 单测' -ForegroundColor Cyan
Push-Location $electronDir
npm run typecheck
if ($LASTEXITCODE -ne 0) { Write-Error '前端类型检查失败' }
npm run test
if ($LASTEXITCODE -ne 0) { Write-Error '前端单测失败' }
Pop-Location

Write-Host '门禁通过：后端测试 + 前端类型检查/单测全部 green' -ForegroundColor Green