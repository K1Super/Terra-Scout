# Terra Scout JRE 裁剪（构建流程）：
# 1) 仓库根构建后端 jar（mvn package）
# 2) 清理旧 electron/resources/jre（jlink 拒绝写入已存在目录）
# 3) jlink 按硬编码基线裁剪 JRE 到 electron/resources/jre
# 参数：-SkipMaven 跳过后端构建（由 build.ps1 串行时已构建过一次）
param([switch]$SkipMaven)

$ErrorActionPreference = 'Stop'

# jlink 不在 PATH 时按 JAVA_HOME 或运行期 java.home 解析，避免依赖环境手工配置
function Resolve-Jlink {
  $cmd = Get-Command jlink -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\jlink.exe') }
  try {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = java -XshowSettings:properties -version 2>&1 | Out-String
    $ErrorActionPreference = $previous
    $m = [regex]::Match($out, 'java[.]home\s*[=:]\s*(\S+)')
    if ($m.Success) { $candidates += (Join-Path $m.Groups[1].Value 'bin\jlink.exe') }
  } catch { }
  foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { return $c }
  }
  throw '未找到 jlink：请安装 JDK 17 并设置 JAVA_HOME，或将 JDK bin 加入 PATH'
}

$root = Split-Path -Parent $PSScriptRoot
$appDir = Join-Path $root 'terra-scout-app'
$jar = Join-Path $appDir 'target\terra-scout-app-0.1.0-SNAPSHOT.jar'
$jreOut = Join-Path $root 'terra-scout-electron\resources\jre'

# 裁剪模块基线（构建流程）。注意：不可用 jdeps 从 spring-boot fat-jar 推导——
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

if (-not $SkipMaven) {
  Write-Host '==> 1/3 构建后端 jar' -ForegroundColor Cyan
  Push-Location $root
  mvn package -DskipTests -q
  Pop-Location
}
if (-not (Test-Path $jar)) {
  Write-Error "构建失败：$jar 不存在（请先在仓库根执行 scripts/build.ps1 或 mvn package）"
}

Write-Host '==> 2/3 清理旧 JRE 目录' -ForegroundColor Cyan
if (Test-Path $jreOut) {
  Remove-Item $jreOut -Recurse -Force
}
# 注意：jlink 要求输出目录不存在，此处不可预先创建，交由 jlink 生成

Write-Host '==> 3/3 jlink 裁剪 JRE' -ForegroundColor Cyan
$jlinkExe = Resolve-Jlink
& $jlinkExe --add-modules ($baseline -join ',') `
  --output $jreOut `
  --strip-debug --compress=2 --no-header-files --no-man-pages

Write-Host "完成：$jreOut" -ForegroundColor Green