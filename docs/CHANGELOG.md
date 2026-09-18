# Terra Scout 变更记录

> 文档版本 V3.0 | 更新日期 2026-09-18

本记录遵循 Keep a Changelog 风格，按时间倒序（新版在前）。变更事实提炼自项目完整开发时间线（历史进度文档已归档于本文件后删除）；时间线仅提供一个时间锚点（最后更新 2026-09-16），故各版本均以该日期标注。

---

## [0.1.0-SNAPSHOT] - 2026-09-16

模块 1~7 全部完成（core / parser / download / task / env / app / electron），端到端装配执行链路落地。

### Added

- **terra-scout-core**：46 错误码枚举（含 SUCCESS(200000) 与中文默认消息、`getHttpStatus()=code/1000`、静态块自检 httpStatus ∈ [200,599]）；`TerraScoutException`（4 构造器 + details 防御性拷贝）；`PathConstants`（数据根 `%USERPROFILE%\.terrascout`，系统属性 `terrascout.data-dir` 可覆盖，含 sdkHome/隔离域布局）；11 枚举；7 个 JPA 实体（与 ddl-migration 2.3 逐列对齐）；11 个 DTO。测试 44，JaCoCo LINE 99.8%（626/627）。
- **terra-scout-parser**：`ProjectTypeDetector`、`PropertyResolver`（`${key}` 求值，MAX_ROUNDS=10，未定义/循环引用 → 422004）、`PomParser`（安全 DOM 解析禁 DOCTYPE/XXE、父链收集、属性合并、七级优先级版本提取、`VersionSource` 枚举）、`ConstraintExtractor`（JAVA/NODE 约束，`.nvmrc`/`.node-version`）。测试 42，JaCoCo LINE 90%，TC-POM-001~010 全过。
- **terra-scout-download**：`Sha256Verifier`、`ResumeableDownloader`（Range+ETag 断点续传，206 追加 / 200 且曾请求 Range→502003，磁盘<2×total→507001，成功 ATOMIC_MOVE）、`Downloader`（编排下载+校验）、`DownloadTask`（可取消）、`ArchiveExtractor`（Zip-Slip/符号硬链接设备/单文件>1GB/文件数>100000 四重检查 → 422010）。测试 26。
- **terra-scout-task**：`TaskStepDefinition`（9 步序列，SDK_INSTALL_TIMEOUT_SECONDS=1800、VERIFY_TIMEOUT_SECONDS=600）、`TaskStepContext`、`TaskStepExecutor`、`TaskStateMachine`（TaskStatusEnum × TaskEvent 11 事件 15 转移）、`LockManager`（ConcurrentHashMap+公平 ReentrantLock）、`HeartbeatMonitor`（HEARTBEAT_INTERVAL_MS=5000 / STALE_THRESHOLD_MS=60000 / DETECT_INTERVAL_MS=30000）、`TaskEngine`（心跳自报、失败重试 + 逆序回滚）。测试 38。
- **terra-scout-env**：`CommandWhitelist`（mvn.cmd/npm.cmd/java.exe/node.exe + arg-pattern 正则，拒绝 → 403002）、`ProcessExecutor`（`.cmd` 经 `cmd.exe /c`、npm 追加 `--ignore-scripts`、先 waitFor 再读流）、`EnvInjector`（注入 JAVA_HOME/NODE_HOME/PATH/隔离 m2·npm，`MAVEN_OPTS=-Dmaven.repo.local={isolation}/m2`）、`EnvScriptGenerator`（env.ps1 生成）。测试 29。
- **terra-scout-app 基础**：`StartupGuard`（prepareDataRoot + ensureDatabasePassword）、`BeansConfig`（暴露非 Spring 领域服务 PomParser/ConstraintExtractor）、`WebConfig` + `TokenValidator`（常量时间比较）+ `TokenInterceptor`（仅 `GET /api/v1/health` 免鉴权，其余 → 401 + 401001）、`TraceContext`、Project/Task/Audit/System 四 Controller、Project/Task/Audit 三 Service、`GlobalExceptionHandler`、`PathSanitizer`（mask 保留最后 2 级）、7 个 JpaRepository、Flyway 单一 V1 基线建 7 表（`ddl-auto: validate`）。测试 34。
- **端到端装配执行链路（8.3）**：`TaskAssemblyOrchestrator`（异步编排，MAX_RETRY=3，planId 不存在 → 404003）、`TaskJournal`（步骤日志，每方法独立 @Transactional）、`SdkInstaller`（真实下载+SHA-256+解压+写 SdkInstallRecord，幂等复用 SUCCESS 记录）、`AssemblySession`、`AbstractStepExecutor`、`SdkVersionMatcher`、8 个具体步骤执行器（DetectProject/ParseManifest/MatchVersion/InstallSdk×2/CreateIsolation/InstallDependencies/BindEnv/VerifyProject）、ThreadPoolTaskExecutor（core 2/max 4/queue 100）。任务 QUEUED→9 步推进→SUCCESS/FAILED/ROLLED_BACK 全程落库。全 reactor 282 测试。
- **SDK 系统级探测（8.4，R29）**：`SystemSdkProber` 重写，策略链探测系统已装 JDK/Node（JAVA_HOME 直读 release、PATH 双布局、`-XshowSettings:properties -version` 合并流解析、`node -p process.execPath`）；TTL 30s 缓存；三源合并（catalog + install_record + 系统探测）；系统项合成 systemInstalled。app 126 测试。
- **SDK 系统探测整改（8.5，R30）**：P0~P3 极高标准方案落地——探测命令绝对路径绑定、保序不可变视图、版本-路径冲突埋点、Python `py -0p` 全量枚举、缓存 single-flight、探测显式超时 PROBE_TIMEOUT_MS=2000、诊断 SPI、平台守卫 isWindows()、ProgramW6432 优先、NVM_HOME 权威、时钟改单调、路径注入缝。多版本发现矩阵覆盖 JAVA/NODE/PYTHON/GO。全 reactor 343 测试。
- **SDK 元数据官方源在线刷新（8.6，R31~R35）**：`SdkOfficialCatalogFetcher` + `AdoptiumJavaFetcher` / `NodeJsFetcher` / `PythonFetcher` / `GoFetcher` + `CatalogHttpClient`（连接 10s/请求 30s、强制 HTTP/1.1）+ `SdkMetadataStore`（(language,version,os,arch) 唯一键 upsert，外部文件强校验）+ `SdkCatalogBootstrap`（首启 extractSeedIfAbsent 装载 seed，全新安装不再空列表）。全 reactor 411 测试。
- **R36~R42 终验**：JAVA 仅 major≥11 的 feature release；Python 发布日期经官方 downloads API 全量数组补全（pre_release 跳过 + DRF 分页兜底）；seed 历史版本日期按 index.json 权威补齐；Go 版本条目含 sizeBytes 作下载进度估算来源；四语言 releaseTime 零 NULL、数字段严格升序。
- **R43/R44**：SDK 取消链路（取消后解压与写记录均不发生、`.zip`/`.part`/半解压目录即时回收、快照收敛 CANCELLED 且 cancelled=true）与卸载链路（物理目录删除、最后引用删除、共享保留、非标准路径护栏跳过）。全 reactor 420 测试。
- **R45**：SDK 安装可自选落位目录（`installDir` 每次安装时选择，自动创建 `{目录}\{语言}\{版本}` 两级结构；相对路径/不可建→400001、目标已存在→409004，复用既有错误码不改 DDL）；已装行新增灰阶「打开」按钮（`sdk:open-path` 以系统资源管理器打开实际落位）；落位 marker 票根 + 卸载护栏升级（标准路径或 marker 存在才物理删除）。
- **R46（导入链路根治）**：修复错误码折叠——前端非 2xx 响应优先透传信封业务码并校验 `code÷1000==HTTP 状态`（422001 不再显示为误导性的 422000），`details.hint` 附加展示；`ProjectTypeDetector` 新增子目录候选枚举，所选目录根无声明文件且直接子目录恰有一个含 pom.xml / package.json 时自动采纳为项目根，无候选/多候选回 422001 并携带 `selectedPath`/`candidates`/`hint` 可执行明细；Home 错误弹窗回显所选目录与真实错误文案；**多模块根治**——`PomParser` 移除 P0 多模块冻结（`assertNotMultiModule`），新增 `resolveModulePoms` 枚举聚合模块；`ConstraintExtractor` 对 MAVEN/MIXED 以 BFS 遍历 `<modules>` 聚合树（深度 ≤5、visited 防环），仅收集已声明版本并按标准化版本去重（根声明优先，`sourceFile` 相对根正斜杠路径），缺失模块目录跳过告警，全部未声明回退单条 UNKNOWN（与单模块一致）。
- **R47（画像与引擎约束根治）**：详情/列表接口补齐 type / constraints / plan 完整画像（修复详情页类型、语言约束、装配计划全空且确认装配禁用的现象）——profile_json 升级为 `{type, constraints}` 结构（旧版纯约束数组回退磁盘重检，不阻断详情）；analyze 与详情均返回预览装配计划（`planId`=projectId，`sdkInstalls` 按约束实时匹配，单条匹配失败仅跳过告警，权威校验在 execute 的 MatchVersionStep）；NPM 的 NODE 约束提取顺序升级为 `.nvmrc` → `.node-version` → package.json `engines.node`（Jackson 结构化解析，保留范围表达式原样，`sourceFile`=package.json）；`SdkVersionMatcher` 支持 `>=`/`>`/`<=`/`<` 比较符（含 `>= 20.0.0` 带空格写法）、`||` 或组合、空格 AND 组合、`20.x` 通配主版本匹配，全部按主版本语义（与 Maven range P0 口径一致）。
- **terra-scout-electron Part A（9.1，并入 app）**：补齐运维端点 `POST /api/v1/sdk/install|uninstall`、`GET /api/v1/sdk/list`、`POST /api/v1/sdk/metadata/reload`、`GET/PUT /api/v1/settings`、`POST /api/v1/system/backup`、`GET /api/v1/system/diagnostic`（脱敏 ZIP，排除 db.properties）、`GET /api/v1/task/{id}/logs`、`POST /api/v1/shutdown`（需 Token，异步关闭先回 200）。56 测试。
- **terra-scout-electron Part B（9.2）**：Electron 桌面端——主进程 `index.ts`（window 1200×800、contextIsolation/sandbox/webSecurity、CSP `connect-src 'self' http://127.0.0.1:*` 禁 unsafe-inline、before-quit 优雅关闭）、`java-process.ts`（spawn Java、解析 `READY <port>` 超时 10s、崩溃自动重启 ≤3 次/2s）、`kernel-info.ts`（token `crypto.randomBytes(32).toString('base64')` 仅内存）、`ipc-handlers.ts`（`sdk:open-path` 打开已装 SDK 目录，仅绝对路径）；preload contextBridge 暴露 `window.kernel`；渲染层 React18 + antd5 + react-router，7 页（Home/ProjectDetail/Tasks/TaskDetail/Sdks/Settings/About）。18 测试全绿。
- **R48（多语言全链路 + SDK 版本选配）**：识别与约束提取扩展四语言——项目类型新增 GO/PYTHON/MIXED（MIXED=2+ 声明文件并存；NPM 检测必须有 package.json）；约束优先序 Python `.python-version` → `pyproject.toml [project] requires-python`、Go `go.mod` 的 `go` 指令（归一 `>=x.y`）；`readFirstLine` 剥离 BOM 防零宽字符静默失配。装配链 9 步 → 11 步（INSTALL_SDK_GO / INSTALL_SDK_PYTHON / `python -m pip` 依赖安装、白名单放行 go/pip/python）；Go 布局 GOROOT=`{home}\go` + GOMODCACHE=`.devenv/go-cache`。SDK 版本选配：`SdkInstallItem.candidates`（首项推荐、N≤5、稳定性排序）+ execute `versionOverrides` 覆盖 + `SdkVersionMatcher.candidateList/overrideVersion` 同源校验，MatchVersionStep 异步裁决（无候选/非法 422006）；前端 ProjectDetail 每行挂候选版本下拉。装配端点收敛为 `POST /api/v1/task/execute`（前端/文档/openapi 三处同步）。全 reactor 490 测试。
- **R49（SDK 目录完整受支持版本线覆盖）**：三个抓取器由「最新稳定版窗口 5 条」升级为「按版本线策略」——PYTHON 按 major.minor 归组，行级 EOL 静态表（官方 PEP 发布计划：3.9→2025-10-31 … 3.14→2030-10-31）排除已停维行（表外新行视为未来受支持行），每条受支持线（3.10/3.11/3.12/3.13/3.14…）按版本倒序至多探测 16 个（安全维护期「仅源码」发布无 windows 元数据时顺延深挖，如 3.11 线 3.11.10~3.11.16 均为源码发布、直到 3.11.9/3.11.8 才有 Windows 产物）、保留该线最近 2 个可安装稳定 patch（官方从未提供 windows 元数据的线如 3.10 不产生候选，不破坏下载→SHA-256 校验闭环）；NODE 按 major 归组，行级支持性按官方发布计划 EOL 静态表裁决（12→2022-04-30 … 26→2029-04-30；实测 index.json 不含 end 字段，不可从数据内判）——含 LTS 条目的行：表内已过 EOL 整线排除（如 18/20）、表上界外未来 major 视为受支持线、表下界下不在表内的远古 major（6/8/10 等）排除；无 LTS 条目的偶数 major 且大于最大 LTS major 的 Current 行纳入（奇数排除），每线保留最新 2 个（SHASUMS 缺失顺延）；GO 仅收录最新 2 个 minor 线（官方仅维护最近两大版本），每线保留最新 2 个 stable。EOL 裁决注入 `Clock`（生产系统 UTC、测试固定时钟保证确定性），移除 CANDIDATE_LIMIT/LIMIT 旧窗口常量。全 reactor 498 测试。
- **R50（内核启动健壮性）**：基准实测启动耗时（冷目录 11.5s / 热目录 8.7~10.1s，Spring 装配占 ~7.3s），定位主因是 Windows 系统防御软件实时扫描 53MB fat jar 造成的类加载波动（JVM 参数无法根治）；落地确定性收益——Electron 启动参数显式化（`-Xms64m -Xmx512m` 消除按物理内存 1/4 估算的堆漂移、`-Dfile.encoding=UTF-8` 消除 Windows 默认 GBK 对路径/响应的不确定编码、`--spring.main.banner-mode=off`），READY 超时 10s→20s 消除慢机误报（防御软件抖动下 8~12s 波动）；启动参数新增单测锁定（22 前端测试全绿）。
- **R51（存储区整改：完整路径 + 快捷打开）**：`/system/info` 的 `dataDir`/`sdkRepoDir`、`/system/backup` 与 `/system/diagnostic` 返回路径由 `PathSanitizer` 脱敏改为**完整真实路径**（与 SDK 列表 `installedPath` 一致；R51 起脱敏仅用于日志落盘 / 错误响应 / 诊断包内容，high-level-design 8.10 已界定）；`info` 补充 `dbSizeBytes`（H2 文件字节数）。前端抽取 `OpenPathButton` 公共组件（复用 SDK 已安装行 R45 `sdk:open-path` 的 FolderOpen + btn 样式与失败提示，Sdks.tsx 删除重复实现改复用它），关于页「存储」区数据目录 / SDK 仓库路径均显示完整路径并附快捷打开按钮（`kvValueLine` 行内布局，长路径截断自适应）。集成测试断言同步（dataDir 等值断言、zipPath 无 `***`）。

### Changed

- **config-reference 收口**：合并重复顶层 `terrascout:` 键；日志键升级为 Boot 3.x 的 `logging.logback.rollingpolicy.*`；数据库密码改外部持久化（D-006）；SDK 元数据改外部文件（D-009）；黑名单改白名单正则（D-008）。
- **TaskService/SdkService 改写**：`execute` 走 confirm 校验 → planId 解析 → 幂等去重 → 落 QUEUED → afterCommit 异步提交编排；`SdkService.install` 去 @Transactional，委托 `SdkInstaller.ensureInstalled`（真实下载）。
- **SystemSdkProber 整改（R30）**：探测面由 JAVA/NODE 扩展为 JAVA/NODE/PYTHON/GO；`SdkService.list` 重写为三源合并。
- **元数据兜底（R34）**：华为云镜像评估后不采用（目录无 sha256 且版本滞后，破坏「下载 → SHA-256 校验」闭环）；NODE 兜底保留 npmmirror（含 SHASUMS256.txt）。

### Fixed

- **R9**：系统 `java.version` 回退值若经 `putIfAbsent` 注入会遮蔽父 POM 推断/编译器插件 release/"未声明"优先级；修复为系统回退仅用于 `${java.version}` 占位符求值，优先级 1~4 只认 pom 已声明属性键。
- **R10~R12（Zip4j API）**：`FileHeader.getExternalFileAttributes()` 在 2.11.5 返回 `byte[]`，unix 类型从高 16 位重建；`extract()` 磁盘预检上溯最近已存在祖先；`MockResponse.setBody` 无 `byte[]` 重载，统一用 `okio.Buffer`。
- **parser 日志卫生**：PomParser 的 JAXP `DocumentBuilder` 增设自定义 `ErrorHandler`，消除默认 stderr Fatal Error 打印。
- **LockManager**：`ReentrantLock` import 修正为 `java.util.concurrent.locks.ReentrantLock`。
- **CommandWhitelist**：`Map.of` 的 `containsKey(null)` 在 JDK 17 抛 NPE，`isAllowedCommand/resolveExecutable` 先判 null。
- **ProcessExecutor**：由"先读流后 waitFor"改为"先 waitFor(超时) 再读流"，超时即时 `destroyForcibly` + 422015。
- **WebIntegrationTest 断言**：AssertJ `assertThat` int/泛型重载歧义，统一显式装箱强转。
- **R31**：NODE 刷新恒 0 条，官方产物名 v 前缀（`node-v24.21.0-win-x64.zip`）修复，正则与 URL 保留 v 前缀。
- **R32**：目录刷新耗时 9 分钟，强制 HTTP/1.1 + 全请求结构化日志，实测降至 13~21s。
- **R33**：GO 偶发 connect timed out，连接类瞬时故障（HttpConnectTimeoutException/ConnectException）自动重试 1 次。
- **R35**：外部元数据文件写错目录，`main()` 把 `--terrascout.data-dir` 归一化进系统属性，DB/日志/元数据/seed/密钥双口径收敛。
- **R30 集成缺陷**：绝对路径探测命令全被 403002 拒绝，`validateProbe` + `resolveProbeExecutable` 按文件名白名单放行绝对路径。
- **R48 执行缺陷**：`go mod download` 恒秒失败（系统 32 位 go.exe 搭配项目 64 位 GOROOT）——Windows 上 JDK 以裸名启动子进程时按父进程（JVM）PATH 定位可执行文件，注入 env 的 PATH 不参与解析；`ProcessExecutor.execute`（装配面）改为 spawn 前把白名单映射裸名按注入 PATH 逐目录解析为绝对路径（未命中回退既有语义，探测面不受影响）。

### Removed

- 移除命令参数黑名单方案，改由 `arg-pattern` 白名单正则覆盖 cmd.exe 全部元字符（D-008，禁止退回黑名单方案）。
- 弃用华为云镜像作为 SDK 元数据兜底源（R34，无 sha256 破坏校验闭环）。

---

## [V2.1] - 2026-09-16

文档阶段完结：28 份文档全面审计修复完成（20 修改 / 9 新建或重写），覆盖范围、架构、数据库、接口、状态机、并发、异常、进程、安全、日志、配置、目录、编码、算法、数据、UI、测试、发布、依赖、术语全链路。

### Changed

- readme.md 升级为「版本 V2.1-Fixed | P0 单机桌面版，零外部中间件」文档索引，确立 master-plan.md 总则最高裁决权与开发顺序建议。
- ddl-migration.md、rest-schema.md、concurrency-model.md、security.md、config-reference.md、test-data.md 重写至 V2.1。
- config-reference.md 落地 D-003 / D-006 / D-009 / D-013：合并重复 `terrascout:` 键、日志键升级 `logging.logback.rollingpolicy.*`、数据库密码外部持久化、SDK 元数据改外部文件、配置优先级四层。

### Added

- master-plan.md（开发总则：权威链、定稿决策 D-001~D-018、质量门禁）。
- rest-schema.md 标准错误码体系（46 码 / 9 族）。