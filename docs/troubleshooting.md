# Terra Scout 常见问题排查

> 文档版本 V3.0 | 更新日期 2026-09-18

本文档按「症状 / 根因 / 解法」组织，汇总 Terra Scout 在开发与使用过程中暴露并已修复的重大问题（历史修复记录已归档于 CHANGELOG.md）。

---

## 1. 启动与进程

### 1.1 H2 多实例文件锁冲突

**症状**：内核启动失败，或数据库报 locked（H2 文件锁冲突）。

**根因**：同一数据根被多个内核实例同时打开，H2 文件级锁冲突。

**解法**：
- Electron 主进程用 `app.requestSingleInstanceLock()` 防多实例。
- `java-process.ts` 重启内核前校验端口与 H2 锁是否已释放。

### 1.2 内核启动超时 / READY 握手失败

**症状**：Electron 等待内核就绪超时，前端停留在"内核启动中"。

**根因**：内核未在约定时间内在 stdout 打印 `READY <port>`，或端口被占用、数据根解析错乱。

**解法**：
- `server.port: 0` 让系统分配空闲端口，Java stdout 打印 `READY <port>`，`java-process.ts` 解析该握手（超时 10s）。
- 内核崩溃时自动重启 ≤3 次/2s，`isShuttingDown` 时跳过；优雅退出走 `POST /shutdown`（等 ≤5s 超时 kill）。
- 数据根用 `--terrascout.data-dir`（等号 / 空格两形），`applyDataDirOverride` 在 `main()` 先归一化进系统属性，保证 DB/日志/元数据/seed/密钥落到同一目录（双口径收敛）。

---

## 2. JDK / Node 检测

### 2.1 假 JDK 检测（javapath stub）

**症状**：SDK 管理页无法识别系统已装 JDK，或把 Oracle javapath 的 stub 误判为有效 JDK。

**根因**：Oracle `javapath\java.exe` 是转发 stub（父目录无 release 文件），直接读文件必落空。

**解法**：探测策略链为 **JAVA_HOME → PATH → javapath stub 识别**，用 `-XshowSettings:properties -version`（整探测周期仅一次）解析 `java.home`/`version`，stdout 与 stderr 合并解析（实测该命令输出全在 stderr）；TTL 30s 缓存避免反复拉起进程。

### 2.2 SDK 探测不识别非 C 盘已装 JDK/Node

**症状**：SDK 管理页无法识别安装路径不在 C 盘的 JDK/Node。JAVA 多版本只显示一个；PYTHON/GO 已装却完全不显示。

**根因**：① `sdk_version` 元数据表全新安装为空、seed 无 loader 导入，列表完全依赖元数据；② javapath stub 只读必落空；③ 各软件源安装位置自由（D/E 盘、nvm 软链目录）；④ 探测面仅限 JAVA/NODE，策略链命中首个即止。

**解法**（SystemSdkProber 重写，R29/R30）：
- JAVA：JAVA_HOME 直读 release + PATH 双布局 + 已发现 home 父目录兄弟扫描 + 标准根一层扫描，一次返回多版本。
- NODE：`node -p process.execPath`（nvm 软链实解真实 home）+ `process.version`（去 v 前缀）；`NVM_HOME` 优先于 `%APPDATA%\nvm`，根目录 `v*/node.exe` 一次列全版本。
- PYTHON：`py -0p` 一次列全部解释器（Launcher），缺失时 PATH 逐项回退。
- GO：GOROOT/VERSION 文件零进程读 + `go version` 回退。
- 系统探测项合成 `systemInstalled=true`，路径返回完整值不做 mask；record 优先于系统项。语言/OS/架构过滤对 catalog 与系统项同等生效。

---

## 3. 构建与打包

### 3.1 GitHub CDN 下载失败

**症状**：electron-builder 二进制拉取超时。

**根因**：GitHub CDN 连接问题。

**解法**：设置镜像环境变量：

```bash
ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/
```

### 3.2 界面样式未生效

**症状**：改动样式后界面仍显示旧样式。

**根因**：前端 dist 未重建，运行的是旧产物。

**解法**：每次样式改动后重新执行前端 build（`cd terra-scout-electron && npm run build`，发布前 `tsc --noEmit` + `vite build`）。

### 3.3 NSIS 打包失败（winCodeSign 符号链接）

**症状**：NSIS 打包在普通终端失败。

**根因**：winCodeSign 中 darwin/ 符号链接需管理员权限处理。

**解法**：NSIS 打包需在 **Administrator PowerShell** 中执行。

---

## 4. 网络与下载

### 4.1 IPv6 路由失败

**症状**：SDK 下载（dl.google.com 走 Fastly）无法直连或超时。

**根因**：本机 IPv6 路由不通。

**解法**：`java-process.ts` 给内核 JVM 加 `-Djava.net.preferIPv4Stack=true`。

### 4.2 无 Content-Length 的分块下载进度不准

**症状**：部分 SDK 用分块传输（chunked），无 Content-Length，下载进度显示不准。

**根因**：总大小未知，进度无法按字节推进。

**解法**：`SdkInstaller` 用 SDK 目录 `sizeBytes` 估算总大小（Go 官方目录条目含 sizeBytes，作为下载进度估算来源，R42）。

### 4.3 元数据刷新报 500000 / NODE 恒 0 条 / 刷新耗时 9 分钟

**症状**：`POST /api/v1/sdk/metadata/reload` 报 500000；NODE 恒 0 条；刷新耗时 9 分钟。

**根因**：
- 官方源不可用（原实现直接抛 500000）。
- NODE 官方产物名带 v 前缀（`node-v24.21.0-win-x64.zip`），抓取正则与下载 URL 用无 v 形式永不匹配，单测夹具与代码同错（测试同构性缺陷）掩盖之。
- JDK HttpClient 默认 HTTP/2 在本机网络协商不稳定，逐请求 30s 卡死。
- GO 官方源（go.dev）与 GitHub 同 CDN，瞬时抖动 connect timed out。
- 外部元数据文件写错目录（`--terrascout.data-dir` 只进 Spring Environment，`PathConstants.dataRoot()` 读系统属性，双口径不一致）。

**解法**：
- 兜底策略：官方源不可用回退本地外部文件，仍 0 抛 `502001 SDK_SOURCE_UNREACHABLE`（不再 500000）。
- NODE 兜底用 npmmirror 镜像（registry.npmmirror.com，含 SHASUMS256.txt，sha256 闭环成立）；华为云镜像目录仅 files 列表、无 sha256 且版本滞后，采用会破坏「下载 → SHA-256 校验」闭环（刚性底线），故不采用（R34）。
- NODE 正则与 URL 保留 v 前缀（R31）。
- 强制 HTTP/1.1 + 全请求结构化日志（R32），实测刷新耗时降至 13~21s。
- 连接类瞬时故障（HttpConnectTimeoutException/ConnectException）自动重试 1 次；非 2xx 与读取超时属确定性结果不重试（R33）。
- 外部元数据写回覆盖根 `config/sdk-metadata.json`（source=official-refresh、schemaVersion 1.0）。
- 日期防回退保护：`releaseTime` 防回退，seed 历史版本按官方 index.json 权威日期补齐（R40）；Python 发布日期经官方 downloads API 全量数组补全（R38）。

---

## 5. 命令执行与白名单

### 5.1 命令白名单误拦（按逻辑名解析 → 403002）

**症状**：探针执行被 403002 拒绝。

**根因**：命令白名单按逻辑名解析，实际 exec 路径与逻辑名不一致导致误拦。

**解法**：探针执行一律走 `exe.toAbsolutePath()` 提交；`CommandWhitelist.validateProbe` 按「尾段文件名恰为探测面白名单可执行」放行绝对路径（`resolveProbeExecutable` 同名解析，大小写不敏感，如 `py.exe`），拒绝其它可执行文件。

### 5.2 命令执行超时形同虚设

**症状**：设置了超时但进程仍长时间阻塞。

**根因**：ProcessExecutor 原先"先读流、后 `waitFor`"，`readAllBytes` 会阻塞至子进程关流，导致超时判定失效（实测等待满 ping 4s）。

**解法**：改为先 `waitFor(超时)`、再读流，超时即时 `destroyForcibly` + 422015（实测 0.23s）。

---

## 6. 前端与其它

### 6.1 koa-connect 上下文泄漏

**症状**：中间件 ctx 串扰，请求间上下文串线。

**根因**：koa-connect 适配器导致 ctx 上下文泄漏。

**解法**：使用原生 Koa 中间件，禁用 koa-connect。

---

## 7. 开发期代码缺陷（供接手开发者参考）

以下为编码期发现并修复的实现陷阱，产线不复发，但新代码需规避。

### 7.1 java.version 回退值遮蔽解析优先级

**症状**：pom 解析把系统 `java.version` 回退值当成已声明属性，遮蔽更高优先级（编译器插件 release、"未声明"语义）。

**根因**：系统回退值经 `putIfAbsent` 注入 `mergedRaw`，落入优先级 3（java.version）。

**解法**（R9）：系统回退仅用于 `${java.version}` 占位符求值，优先级 1~4 只认 pom 已声明属性键（`declaredProps`）。

### 7.2 Zip4j API 误用

**症状**：解压安全校验 / 磁盘预检异常。

**根因**（R10~R12）：`FileHeader.getExternalFileAttributes()` 在 2.11.5 返回 `byte[]`（4 字节小端），unix 类型须从高 16 位重建；`extract()` 磁盘预检在 targetDir 尚未创建时需上溯最近已存在祖先做 `getFileStore`；`MockResponse.setBody` 无 `byte[]` 重载。

**解法**：按上述修正；测试统一用 `okio.Buffer` 包装。

### 7.3 锁 / 集合 API 陷阱

**症状**：编译报错或运行期 NPE。

**根因**：
- LockManager 的 `ReentrantLock` import 误写为 `java.util.concurrent.ReentrantLock`（应为 `java.util.concurrent.locks.ReentrantLock`）。
- `Map.of`（ImmutableCollections.MapN）的 `containsKey(null)` 在 JDK 17 抛 NPE，`isAllowedCommand/resolveExecutable` 需先判 null。

**解法**：修正 import；白名单命中前先判 null。

### 7.4 AssertJ 断言重载歧义

**症状**：测试断言编译/运行期出现重载歧义。

**根因**：`assertThat(resp.getStatusCode().value())`（int）与 `assertThat(JsonPath.read(...))`（泛型 Object）共用会触发 `assertThat(IntPredicate)/assertThat(Predicate)` 重载歧义。

**解法**：统一显式装箱 `Integer.valueOf(...)` / `(Integer)` / `(String)` 强转。

### 7.5 XML 解析日志污染

**症状**：构建日志中出现 JAXP 默认 ErrorHandler 向 stderr 打印 Fatal Error 的噪音。

**根因**：PomParser 的 JAXP `DocumentBuilder` 未设自定义 `ErrorHandler`（invalid-xml 样本触发）。

**解法**：增设自定义 `ErrorHandler`，仅在解析异常时抛出并转 422004。