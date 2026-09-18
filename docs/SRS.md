# Terra Scout 需求规格说明（SRS）

> 文档版本 V3.0 | 更新日期 2026-09-18 | 适用范围 P0 单机桌面版
> 本文档为需求与工程治理最高权威，其余文档与本档冲突时以本档裁决（见 §6.1）。

## 1. 引言

### 1.1 目的

本文档定义 Terra Scout 环境感知编排平台 P0 单机桌面版的需求规格，作为开发、测试与验收的统一基线。范围界定见 §3；定稿决策、权威链与工程治理要求见 §6。

### 1.2 读者

产品负责人、技术负责人、开发、测试、验收人员。

### 1.3 术语约定

| 术语 | 含义 |
|---|---|
| Terra Scout | 环境感知编排平台，Windows 单机桌面工具 |
| 内核 | Spring Boot 单 JVM 服务端内核，内嵌 H2，零外部中间件 |
| SDK | 软件开发工具包（Java / Node 运行时） |
| 任务引擎 | 负责任务调度、状态机、心跳、锁、回滚的内核组件（terra-scout-task） |
| 装配 | SDK 下载安装 + 依赖隔离装配的完整流程 |
| 数据根 | `%USERPROFILE%\.terrascout`，唯一数据根（D-003） |
| 项目隔离域 | `{projectRoot}\.devenv\`，仅存 m2、npm-cache、env.ps1（D-004） |
| SDK 仓库 | `%USERPROFILE%\.terrascout\sdks`，全局共享的 SDK 安装目录，跨项目复用（REUSE 语义） |
| 进程级注入 | 经 `ProcessBuilder` 向任务进程注入环境、输出 `env.ps1`，不改系统全局 |
| 项目画像 | 解析项目配置后得到的语言、版本、依赖等信息 |
| 版本约束 | 项目声明的 SDK 版本要求 |
| 装配计划 | 根据约束生成的 SDK 安装和依赖安装方案 |
| 幂等键 | 保证相同请求只执行一次的唯一标识 |
| 心跳 | 任务执行期间定期更新状态，用于检测崩溃 |
| 回滚 | 任务失败后清理已完成的步骤 |
| 诊断包 | 包含日志、配置、环境信息的 ZIP 文件，用于排查问题 |
| EOL | End Of Life，版本停止维护 |
| CVE | Common Vulnerabilities and Exposures，公共漏洞披露 |
| Zip-Slip | 解压路径穿越攻击，通过 `../` 逃逸目标目录 |
| SBOM | Software Bill of Materials，软件物料清单 |
| WAL | Write-Ahead Logging，预写日志 |

## 2. 产品概述

### 2.1 定位

Terra Scout 是 Windows 单机桌面工具：解析 Java/Node 项目的环境声明，自动完成 SDK 下载安装、依赖隔离装配与进程级环境注入。

### 2.2 核心能力

- 项目解析：解析 `pom.xml`、`package.json` 等环境声明，输出项目画像与装配计划。
- SDK 管理：下载、断点续传、SHA256 校验、安装、卸载。
- 任务引擎：任务调度、状态机、暂停/恢复/重试/崩溃恢复、回滚。
- 环境注入：进程级环境注入，输出 `env.ps1`；依赖隔离（`.devenv/`）。
- 配置与元数据：统一数据根、运行时设置持久化、SDK 元数据外部文件热重载。
- 安全：Token 鉴权、命令白名单、Zip-Slip 防护、审计日志。

### 2.3 系统边界总览

双进程架构：Electron UI ↔ HTTP（127.0.0.1 + Token）↔ Spring Boot 单 JVM 内核，内嵌 H2 文件库，零外部中间件。P0 仅 Windows 10/11、Java/Node、Maven/npm。

## 3. 范围界定

### 3.1 P0 做

| 维度 | 范围 |
|---|---|
| 操作系统 | Windows 10 / 11 |
| 语言 | Java、Node.js |
| 构建工具 | Maven、npm |
| 项目文件 | `pom.xml`、`package.json`、`package-lock.json`、`.nvmrc`、`.node-version` |
| SDK 能力 | 下载、断点续传、SHA256 校验、安装、卸载 |
| 环境能力 | `ProcessBuilder` 进程级注入，输出 `env.ps1` |
| 项目隔离 | `.devenv/`，隔离 Maven 本地仓库与 npm 缓存 |
| 任务 | JVM 线程池，H2 持久化，暂停/恢复/重试/崩溃恢复 |
| 并发 | `ReentrantLock` |
| 调度 | `ScheduledExecutorService` |
| 元数据 | 本地 JSON，加载至 H2，支持文件重载 |
| AI | UI 开关占位，不调用 LLM |
| UI | Electron + React，仅 GUI |
| 安全 | Token 拦截器、命令白名单、Zip-Slip 防护、审计日志 |
| 发布 | Windows NSIS，jlink 裁剪 JRE，未签名，内测 |

### 3.2 P0 不做

- 不做 macOS / Linux。
- 不做 Python / Go（工作流：解析/装配/执行；~~系统 SDK 管理页“已装 SDK 显示”已由 R30 探测面引入 Python/Go 只读探测~~，任务执行命令面仍限 JAVA/NODE）。
- 不做全局环境变量修改。
- 不做 AI 调用、RAG、诊断。
- 不做自动更新。
- 不做代码签名、公证。
- 不做 SBOM。
- 不做遥测。
- 不做 IDE 插件。
- 不做 CI/CD 集成。

### 3.3 P1 / P2

**P1**：Python、Go、macOS、Linux、LangChain4j、AST、自动更新、签名公证。
**P2**：IDE 插件、CI/CD、企业私有源。

### 3.4 边界冻结规则

- P0 期间任何越界需求进 P1。
- 越界需求需产品、技术、项目经理三方评审。
- 评审通过后需重新评估排期。

## 4. 功能需求

功能需求按能力域组织，来源于 V2.1 定稿需求基线（§6）与范围冻结清单（§3）。

### 4.1 项目解析

- 识别项目类型：`ProjectTypeDetector`、`PomParser`、`PropertyResolver`。
- 项目文件：`pom.xml`、`package.json`、`package-lock.json`、`.nvmrc`、`.node-version`。
- Maven 多模块为 P0 不支持项，冻结为抛 `422001`（以主计划裁决，见附录 A M8）。
- 无 `java.version` 声明时约束为 UNKNOWN，不报错（不可解析版本）。
- 测试基线：TC-POM-001~010 全部通过。

### 4.2 SDK 管理

- 能力：下载、断点续传、SHA256 校验、安装、卸载。
- 全局共享仓库：`{data-root}\sdks\{language}\{version}`，跨项目复用（D-004）。
- SDK 元数据为外部文件 `{data-root}\config\sdk-metadata.json`，首启从 jar 内种子提取，支持热重载端点（D-009）。
- `sdk_version` 表与 sdk-metadata-schema.json 字段一一对应，含 lts / eol / eol_date / cve_count / highest_cve_severity / license / distribution / vendor / size_bytes（D-017）。
- SDK 安装步骤超时 30 分钟（D-018）。
- 下载模块：断点续传 / SHA256 / 解压，测试须覆盖 MockWebServer 模拟断网 / 损坏 / Zip-Slip。

### 4.3 任务引擎

- JVM 线程池调度，任务状态 H2 持久化，支持暂停/恢复/重试/崩溃恢复。
- 状态机 / 心跳 / 锁 / 回滚；转移表全覆盖测试 + 心跳超时单测。
- 心跳：任务线程自报（5s），检测线程只扫描（30s），阈值 60s；启动时恢复扫描（D-007）。
- 所有成功响应 = HTTP 200 + code 200000；异步语义由 `taskId` + 轮询表达；`202` 弃用（D-002）。
- 幂等重放：返回 200 + 200000 + 首次结果，不返回冲突错误码（D-001）。
- 无 SSE，任务进度以轮询表达；P1 可引入 SSE。

### 4.4 环境注入

- `ProcessBuilder` 进程级注入，输出 `env.ps1`；不修改系统全局环境变量、注册表、PATH。
- 项目隔离域 `.devenv/`：仅存 m2、npm-cache、env.ps1；依赖按项目隔离。
- 命令执行：命令白名单 + 参数白名单正则 + 超时 + 审计（D-008）。
- 注入正确性与注入拦截均有对应验收用例（TC-005 / TC-010）。

### 4.5 配置

- 唯一数据根 `%USERPROFILE%\.terrascout`（db / logs / config / sdks / db\backup / diagnostics，D-003）。
- 运行时设置持久化 `config/user-settings.json`（镜像 / 日志级别 / AI 开关，D-013）。
- 配置优先级：内置默认 → user-settings.json → db.properties（仅数据库密码）→ 命令行参数。
- 路径常量集中定义在 terra-scout-core 的 `PathConstants`，代码中禁止第二套硬编码路径。

### 4.6 安全

- Token 鉴权：HTTP 请求经 Token 拦截器校验；Token 经命令行传递（P0 已接受风险，D-016）。
- 命令执行：禁止字符串拼接构造命令，一律 `List<String>` + 命令白名单 + 参数白名单正则（D-008）。
- 解压防护：四重检查（Zip-Slip / 符号链接 / 压缩比 / 文件数）。
- 审计日志：`biz_id` 非唯一（加索引），hash 链防无意篡改（D-015）。
- 数据库密码：首启 SecureRandom 生成后持久化 `config/db.properties`，ACL 仅当前用户（D-006）。

### 4.7 UI

- Electron + React + Ant Design 5 + Monaco。
- ui-pages 共 7 页；进程管理（双进程优雅退出 + Java 崩溃自动重启）。
- 覆盖端点：项目列表/详情/删除、任务列表/日志、审计查询、设置、系统端点等（共 11 个端点，`/sdk/list` 响应含 `recordId`/`installedPath`，冗余 `/ai/config` 并入 `/settings`）。
- AI：UI 开关占位，不调用 LLM；关闭 AI 不影响核心功能（TC-014）。

## 5. 非功能需求

### 5.1 安全

安全红线（违反即拒 PR，完整保留）：

1. 禁止字符串拼接构造命令；一律 `List<String>` + 命令白名单 + 参数白名单正则（D-008）。
2. 禁止修改系统全局环境变量 / 注册表 / PATH（ADR-007）。
3. 所有外部输入路径必须规范化（`toRealPath` + 目标前缀校验）后使用。
4. Token 不落盘（命令行传递的已知暴露见 D-016）；数据库密码仅存 db.properties（ACL 600）。
5. 解压必须过四重检查：Zip-Slip / 符号链接 / 压缩比 / 文件数。
6. 新增外部命令必须同步：白名单 + 超时 + 审计。
7. 日志与错误响应中的路径必须脱敏（保留最后 2 级）。

### 5.2 性能

- 项目分析响应时间 < 2s（TC-001 判定）。
- SDK 安装成功率 ≥ 98%、断点续传成功率 ≥ 98%、回滚成功率 100%。

### 5.3 可靠性

- 崩溃恢复：kill Java 进程后重启，任务标记 FAILED（错误码 500003）并提供重试（TC-008）。
- 心跳与超时检测（D-007），启动时恢复扫描。
- 电源中断后 H2 完整、任务可恢复；磁盘满、权限拒绝有明确提示。

### 5.4 数据质量

- 错误码 46 码 / 9 族；总规则：HTTP 状态码 == code ÷ 1000（6 位数字 = 前 3 位 HTTP + 后 3 位业务序号）。
- 错误码变更四表同步：api-spec.md §3.1、`TerraScoutError` 枚举、high-level-design.md §6、coding-standards.md §8。
- 枚举变更三表同步：api-spec / database-design 枚举表 / core 枚举类。

### 5.5 可维护性

- 依赖最小化：不为单一工具类引入新依赖（D-012）。
- 路径常量集中管理（`PathConstants`），禁止第二套硬编码路径。
- ADR 只追加、不修改历史；文档先行、代码 PR 引用文档章节号。
- 单一 Flyway 基线 `V1__init.sql`，`flyway_schema_history` 唯一账本（D-005）。

## 6. 工程治理与需求基线

### 6.1 文档权威链与冲突裁决

```text
SRS.md（需求基线，最高）
  > high-level-design.md（架构决策 ADR，只增不改）
  > 各专项文档（database-design / api-spec / assets/openapi / coding-standards / testing / deployment-guide）
  > CHANGELOG.md（变更记录，仅追溯）
```

规则：

1. **冲突即缺陷**：发现两处文档口径不一，按权威链裁决，当次变更内回改低优先级方，禁止双轨并存。
2. **文档先行**：改代码前先改文档；代码 PR 描述必须引用对应文档章节号。
3. **ADR 只追加**：不修改历史记录；推翻旧决策需新增 ADR 并标注 `supersedes ADR-0xx`。
4. **四表同步**：错误码变更必须同步 4 处（见 §5.4）；枚举变更必须同步 3 处（api-spec / database-design 枚举表 / core 枚举类）。

### 6.2 定稿决策 D-001 ~ D-018

以下决策为 V2.1 全量定稿，编码期间不得更改；如需更改走 §6.3 变更流程。

| 编号 | 决策 | 理由 | 影响文档 |
|---|---|---|---|
| D-001 | 错误码 46 码 / 9 族；鉴权失败 = HTTP 401 + `401001`；命令注入拦截 = `403002`；队列满 = `409006`；幂等重放 = 200 + 首次结果（不设错误码） | HTTP 状态码必须等于 code 前 3 位（强约束），禁止 401 载荷配 403 码的错位 | api-spec、assets/openapi、high-level-design、coding-standards、testing |
| D-002 | 所有成功响应 = HTTP 200 + code 200000；异步语义由 `taskId` + 轮询表达 | 单一成功口径，`202` 弃用；P1 可引入 SSE | api-spec、assets/openapi、high-level-design |
| D-003 | 唯一数据根 `%USERPROFILE%\.terrascout`（db / logs / config / sdks / db\backup / diagnostics） | 单根便于备份、权限、卸载清理；卸载保留、彻底清理走选项 | deployment-guide、high-level-design |
| D-004 | SDK 仓库全局共享：`{data-root}\sdks\{language}\{version}`；项目隔离域 `{projectRoot}\.devenv\` 仅存 m2、npm-cache、env.ps1；`scope` 字段表达记录归属（PROJECT = 由项目装配触发），不表达物理位置 | 跨项目复用 SDK（REUSE 语义成立），避免每项目重复下载 200-300MB；依赖仍按项目隔离 | deployment-guide、high-level-design、coding-standards、testing |
| D-005 | 单一基线 `V1__init.sql` 含 P0 全部最终表结构；Flyway 唯一账本（`flyway_schema_history`）；已发布迁移永不修改；`error_code` 一律 INT | 项目尚无已部署版本，不存在增量历史；消除 V1/V2/V4 互相矛盾 | database-design |
| D-006 | H2 不用 `AUTO_SERVER`；数据库密码首启 SecureRandom 生成后持久化 `config/db.properties`（ACL 仅当前用户），禁止每次启动随机 | 单 JVM 无需混合模式；随机密码导致文件库二次启动无法打开 | database-design、deployment-guide、high-level-design |
| D-007 | 心跳：任务线程自报（5s），检测线程只扫描（30s），阈值 60s；启动时恢复扫描 | 全局调度线程盲目刷新所有 RUNNING 心跳会掩盖卡死任务（V2.0 缺陷已修复） | high-level-design |
| D-008 | 命令执行：命令白名单 + 参数白名单正则 `^[A-Za-z0-9@+=:,._/\\-]+$`（覆盖 cmd.exe 全部元字符 `& | ; > < ` $ % ^ ( ) ! " '`）+ 超时 + 审计；黑名单方式废弃 | 黑名单漏掉 `%` `^` `(` `)` `!` 仍可变量展开 | high-level-design、deployment-guide |
| D-009 | SDK 元数据为外部文件 `{data-root}\config\sdk-metadata.json`，首启从 jar 内种子提取，支持热重载端点 | classpath 资源打包后不可编辑，与“支持文件重载”需求矛盾 | deployment-guide、api-spec、assets/openapi |
| D-010 | jlink 模块清单以 `jdeps` 实测为准（基线见 deployment-guide.md），CI 中校验 | 示例命令曾漏 `java.naming`/`jdk.unsupported`，打出的 JRE 起不来 | deployment-guide |
| D-011 | 测试库 = H2 原生模式（与生产一致）；禁用 Testcontainers（零 Docker）；网络场景用 MockWebServer | 测试通过必须代表生产行为；MODE=MySQL 与生产模式不一致 | testing |
| D-012 | 依赖最小化：不为单一工具类引入新依赖（不引入 Guava 仅为 ThreadFactory，自研 10 行实现） | 减少供应链与 jlink 体积 | high-level-design、deployment-guide |
| D-013 | 运行时设置持久化 `config/user-settings.json`；优先级：内置默认 → user-settings.json → db.properties（仅数据库密码）→ 命令行参数 | 打包的 application.yml 不可写，运行时可变项需外部持久化 | deployment-guide、api-spec |
| D-014 | 项目删除仅删数据库记录，不触碰磁盘 `.devenv` | 用户文件安全默认；重新导入即恢复 | api-spec、assets/openapi |
| D-015 | 审计日志 `biz_id` 非唯一（加索引）；hash 链防无意篡改 | 一个业务 ID 有多条审计记录是常态，唯一约束插入即失败 | database-design |
| D-016 | Token 经命令行传递为 P0 已接受风险（单用户桌面、进程生命周期内有效） | 同机其他进程可读进程命令行；P1 改 stdin/命名管道握手 | high-level-design |
| D-017 | `sdk_version` 表与 assets/sdk-metadata-schema.json 字段一一对应（含 lts / eol / eol_date / cve_count / highest_cve_severity / license / distribution / vendor / size_bytes） | 版本匹配算法依赖这些字段，原 DDL 缺失导致算法无法实现 | database-design、assets/sdk-metadata-schema.json |
| D-018 | SDK 安装步骤超时 30 分钟 | 300MB 级下载在慢链路下 10 分钟不够，98% 成功率指标要求余量 | high-level-design |

### 6.3 文档变更流程

```text
1. 提 PR 修改文档
2. 同步所有引用方（错误码四表 / 路径表 / 枚举三表）
3. 更新 CHANGELOG.md 变更记录
4. 代码 PR 引用文档章节号
```

## 7. 验收标准

### 7.1 验收用例（TC-001 ~ TC-014）

验收用例完整保留如下，其执行环境、混沌测试与验收流程的详述见 testing.md 第 4 节。

#### TC-001：项目导入分析

| 项 | 内容 |
|---|---|
| 前置 | 准备 Maven 项目，`pom.xml` 声明 Java 17 |
| 步骤 | 1. 启动工具；2. 导入项目；3. 等待分析 |
| 期望 | 返回项目画像，推荐 Java 17，展示装配计划 |
| 判定 | 响应时间 < 2s，推荐版本正确 |

#### TC-002：SDK 安装

| 项 | 内容 |
|---|---|
| 前置 | 无 Java SDK |
| 步骤 | 1. 确认装配；2. 等待下载安装 |
| 期望 | SDK 安装到 `%USERPROFILE%\.terrascout\sdks\java\17.0.9`（全局仓库，D-004） |
| 判定 | 安装成功，SHA256 校验通过 |

#### TC-003：断点续传

| 项 | 内容 |
|---|---|
| 前置 | 下载中断 |
| 步骤 | 1. 下载 50% 时断网；2. 恢复网络；3. 继续下载 |
| 期望 | 从 50% 继续，不重新下载 |
| 判定 | 续传成功，SHA256 校验通过 |

#### TC-004：项目隔离

| 项 | 内容 |
|---|---|
| 前置 | 两个项目，Java 17 和 Java 21 |
| 步骤 | 1. 分别装配；2. 检查 `.devenv/m2` |
| 期望 | 各自独立，互不影响 |
| 判定 | 依赖隔离正确 |

#### TC-005：进程级环境注入

| 项 | 内容 |
|---|---|
| 前置 | 装配完成 |
| 步骤 | 1. 在项目根目录 PowerShell 执行 `. .\.devenv\env.ps1; mvn -version` |
| 期望 | 输出 Java 17，不影响系统全局 |
| 判定 | 进程环境正确，全局未变 |

#### TC-006：任务暂停与恢复

| 项 | 内容 |
|---|---|
| 前置 | 装配任务执行中 |
| 步骤 | 1. 暂停；2. 恢复 |
| 期望 | 从断点继续 |
| 判定 | 任务状态正确 |

#### TC-007：任务失败回滚

| 项 | 内容 |
|---|---|
| 前置 | 依赖安装失败（模拟） |
| 步骤 | 1. 触发失败；2. 执行回滚 |
| 期望 | 逆序回滚，清理 `.devenv` |
| 判定 | 回滚成功 |

#### TC-008：崩溃恢复

| 项 | 内容 |
|---|---|
| 前置 | 任务执行中 |
| 步骤 | 1. kill Java 进程；2. 重启工具 |
| 期望 | 任务标记 FAILED（错误码 500003），提供重试 |
| 判定 | 恢复流程正确 |

#### TC-009：磁盘空间不足

| 项 | 内容 |
|---|---|
| 前置 | 磁盘剩余 < 500MB |
| 步骤 | 1. 触发下载 |
| 期望 | 提示磁盘不足，错误码 507001 |
| 判定 | 正确提示 |

#### TC-010：命令注入防护

| 项 | 内容 |
|---|---|
| 前置 | - |
| 步骤 | 1. 尝试执行 `mvn; rm -rf` |
| 期望 | 拒绝执行，错误码 403002 |
| 判定 | 注入被拦截 |

#### TC-011：Zip-Slip 防护

| 项 | 内容 |
|---|---|
| 前置 | 构造含 `../../evil` 的 ZIP |
| 步骤 | 1. 触发解压 |
| 期望 | 拒绝解压，错误码 422010 |
| 判定 | 路径穿越被拦截 |

#### TC-012：Token 鉴权

| 项 | 内容 |
|---|---|
| 前置 | - |
| 步骤 | 1. 不携带 Token 请求；2. 携带错误 Token |
| 期望 | 返回 401 |
| 判定 | 鉴权生效 |

#### TC-013：审计日志

| 项 | 内容 |
|---|---|
| 前置 | 执行操作 |
| 步骤 | 1. 查询审计日志 |
| 期望 | 记录完整，hash 链正确 |
| 判定 | 日志完整 |

#### TC-014：AI 开关

| 项 | 内容 |
|---|---|
| 前置 | AI 关闭 |
| 步骤 | 1. 执行所有核心功能 |
| 期望 | 全部可用 |
| 判定 | 关闭 AI 不影响核心 |

### 7.2 出厂门禁

- 所有 P0 测试用例通过。
- 混沌测试通过（测试方法详见 testing.md 第 4 节）。
- 指标达标：
  - SDK 安装成功率 ≥ 98%
  - 断点续传成功率 ≥ 98%
  - 回滚成功率 100%
  - 覆盖率 ≥ 75%
- 安全测试通过。
- 文档齐全。

### 7.3 与测试文档的引用关系

- 验收测试环境与验收判定流程已收敛至 testing.md §4 详述，本节仅保留门禁指标。
- 混沌测试（断网 / 磁盘满 / kill 进程 / 断电 / 只读目录）已移至 testing.md §4 详述。
- 验收用例的执行依赖 testing.md 第 3 节的样本数据与第 2 节的测试环境。