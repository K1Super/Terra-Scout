# 裁剪 JRE（deployment-guide.md 构建流程，D-010）：
# 1) 仓库根构建后端 jar（mvn package -DskipTests）
# 2) 清理旧 resources/jre（jlink 拒绝写入已存在目录）
# 3) jlink 裁剪 12 模块到 resources/jre
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$appDir = Join-Path $root 'terra-scout-app'
$jar = Join-Path $appDir 'target\terra-scout-app-0.1.0-SNAPSHOT.jar'
$jreOut = Join-Path $PSScriptRoot '..\resources\jre'

# 裁剪模块基线（docs/deployment-guide.md 构建流程）。注意：不可用 jdeps 从 spring-boot fat-jar 推导——
# 依赖嵌在 BOOT-INF/lib 的嵌套 jar 中，jlink 模块系统读不到，jdeps 只会给出 java.base。
# 故基线为硬编码清单；若新增第三方库导致运行期缺模块，报错后按错误提示补充。
$baseline = @(
  'java.base',
  'java.sql',
  'java.xml',
  'java.naming',
  'java.management',
  'java.net.http',
  'java.transaction.xa',
  'jdk.crypto.ec',
  'jdk.unsupported',
  'jdk.zipfs',
  'jdk.charsets',
  'jdk.localedata'
)

Write-Host '==> 1/3 构建后端 jar' -ForegroundColor Cyan
Push-Location $root
mvn package -DskipTests -q
Pop-Location
if (-not (Test-Path $jar)) {
  Write-Error "构建失败：$jar 不存在"
}

Write-Host '==> 2/3 清理旧 JRE 目录' -ForegroundColor Cyan
if (Test-Path $jreOut) {
  Remove-Item $jreOut -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $jreOut | Out-Null

Write-Host '==> 3/3 jlink 裁剪 JRE' -ForegroundColor Cyan
jlink --add-modules ($baseline -join ',') `
  --output $jreOut `
  --strip-debug --compress=2 --no-header-files --no-man-pages

Write-Host "完成：$jreOut" -ForegroundColor Green