# Terra Scout 快速开始

> 文档版本 V3.0 | 更新日期 2026-09-18

本文档面向首次接手 Terra Scout 的开发者，回答"怎么跑起来、怎么用、按什么顺序开发"三个问题。产品定位：本地开发环境感知与编排工具（双进程：Electron UI ↔ HTTP(127.0.0.1 + Token 鉴权) ↔ SpringBoot 单 JVM 内核，内嵌 H2，零外部中间件）。

## 1. 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| 操作系统 | Windows 10 / 11 | 单机桌面版，`isWindows()` 平台守卫，中文 Windows 字符集 `jdk.charsets` |
| JDK | 17+（OpenJDK Temurin / Zulu） | 不使用 Oracle JDK；release 17 |
| Maven | 3.9+ | enforcer 门禁强制 |
| Node.js | 18+ | 仅 electron 模块需要 |
| 第三方中间件 | 无 | 内嵌 H2，无 Redis/RabbitMQ/Nacos 等外部服务 |

> 技术栈明细：JDK 17 / Maven 3.9+ / Spring Boot 3.2.5 / H2 2.2.x / Flyway 9.22.x / OkHttp 4.12.0 / Zip4j 2.11.5 / JUnit 5.10 + AssertJ 3.25 / Node 18+（electron 模块）。

## 2. 构建与启动

### 2.1 后端构建（Maven reactor）

质量门禁（每个模块 DoD 的一部分，必须用 `verify`）：

```bash
mvn clean verify
```

> 注意：必须用 `mvn clean verify`，`mvn clean package` **不会**触发绑定在 verify 阶段的 JaCoCo 门禁。

打包（跳过测试，用于发布构建）：

```bash
mvn clean package -DskipTests
```

### 2.2 前端构建（electron，独立 npm 工程，不进 Maven reactor）

```bash
cd terra-scout-electron && npm ci && npm run build && npm test
```

发布前的类型检查与构建（Part B 门禁）：

```bash
tsc --noEmit
vite build
```

### 2.3 双进程启动与 READY 握手

- `server.port: 0`：内核端口设为 0，由系统分配空闲端口。
- 内核启动后经 Java stdout 打印 `READY <port>` 传给 Electron（`src/main/java-process.ts` 解析 `READY <port>`，超时 10s）。
- Electron 主进程 `src/main/index.ts` 负责生命周期；`src/main/java-process.ts` spawn Java，崩溃自动重启 ≤3 次/2s，`isShuttingDown` 跳过，优雅退出 `POST /shutdown` 等 ≤5s 超时 kill。
- 命令行参数（Electron 传入：端口 / Token / 数据目录），端到端实测启动形态：

```bash
fat jar + --terrascout.token + --server.port=0 + 数据根覆盖（stdout 轮询 READY <port>）
```

- 数据根覆盖支持 `--terrascout.data-dir`（等号 / 空格两形），`TerraScoutApplication.applyDataDirOverride` 在 `main()` 先归一化进系统属性，DB/日志/元数据/seed/密钥解析到同一目录（双口径收敛）。

## 3. 基本使用流程

以 7 页面为入口的完整链路：**Home / ProjectDetail / Tasks / TaskDetail / Sdks / Settings / About**。

| 环节 | 页面 / 入口 | 行为 |
|---|---|---|
| 1. 创建项目 | Home → `dialog:select-directory`（系统目录选择，项目导入） | 选择项目根目录后发起 `POST /api/v1/project/analyze` |
| 2. 解析 | ProjectDetail | 解析 pom.xml / package.json / go.mod / .python-version / pyproject.toml，产出 JAVA / NODE / GO / PYTHON 约束（`ProjectConstraint`），类型 UNKNOWN → 422001 |
| 3. 配置 SDK | Sdks 页 | 查看已装 SDK（catalog + install_record + 系统探测三源合并），`POST /api/v1/sdk/install` 安装（版本可选），支持 `GET /api/v1/sdk/list`、`POST /api/v1/sdk/metadata/reload` |
| 4. 执行任务 | Tasks 页 → `POST /api/v1/task/execute`（confirm 必须 true） | 落 QUEUED，异步编排走 11 步：探测→解析→版本匹配（versionOverrides 选配校验）→装 SDK（JAVA/NODE/GO/PYTHON 分步）→建隔离域→装依赖→注入环境→验证，全程 QUEUED→RUNNING→SUCCESS/FAILED/ROLLED_BACK 落库 |
| 5. 查看日志 | TaskDetail → `GET /api/v1/task/{id}/logs` | 拼 command_execution 日志展示 |

辅助入口：

- Settings：`GET/PUT /api/v1/settings` 读写 `user-settings.json`（运行时可变项热生效）。
- About / 系统信息：`GET /api/v1/system/info`（version/javaVersion/dataDir/sdkRepoDir，路径经 `PathSanitizer.mask()` 脱敏）、`GET /api/v1/system/health-status`。
- 环境注入验证：任务完成后可直接在安装目录下执行对应命令（`mvn -version` / `node --version` / `go version` / `python --version`；`.devenv` 隔离 m2 / npm-cache / go-cache，`env.ps1` 记录了完整注入环境），默认工作目录为项目目录。
- SDK 版本选配（裁决 R48）：ProjectDetail 装配计划里每个 SDK 安装行附带候选版本下拉（首项为自动推荐），确认装配时以 `versionOverrides` 携带所选版本；非法版本在任务 `MATCH_VERSION` 步失败（422006）并回滚。

## 4. 开发顺序建议

### 4.1 文档研读顺序

1. **SRS.md 需求基线（必读）**
2. high-level-design.md 总体设计与架构决策
3. database-design.md 数据库层
4. api-spec.md（含 assets/openapi.yaml）接口定义与异常
5. coding-standards.md 编码与 UI 规范
6. testing.md 编写单元/集成测试
7. deployment-guide.md 打包发布与配置

### 4.2 按模块生成代码顺序

1. terra-scout-core（领域模型、枚举、错误码、PathConstants）
2. terra-scout-parser（解析器）
3. terra-scout-download（下载器）
4. terra-scout-task（任务引擎）
5. terra-scout-env（环境注入）
6. terra-scout-app（Controller、Service、Repository）
7. terra-scout-electron（UI）

> 每生成一个模块，立即写测试；集成测试在 W6 开始；混沌测试在 W8 开始。
> 先设计，后代码。先内核，后 UI。先 P0，后 P1。
> 一切冲突以 SRS.md 需求基线裁决；错误码四表同步、枚举三表同步（SRS.md §6）。

### 4.3 模块划分速览

```text
terra-scout-app        SpringBoot 启动、Controller、Service、Repository
terra-scout-core       领域模型、枚举、错误码、DTO、PathConstants
terra-scout-parser     pom/package.json 项目解析
terra-scout-download   SDK 下载、断点续传、校验、解压
terra-scout-task       任务引擎、状态机、心跳、锁
terra-scout-env        环境注入、进程执行、命令白名单、脚本生成
terra-scout-electron   Electron + React UI
```

## 5. 下一步

- [SRS.md](./SRS.md)：需求规格说明（范围与验收准则）。
- [high-level-design.md](./high-level-design.md)：总体架构设计（模块依赖图、数据流图、时序图）。
- [api-spec.md](./api-spec.md)：REST API 接口契约 + 标准错误码体系（46 码 / 9 族）。
- [database-design.md](./database-design.md)：数据库设计与迁移策略。
- [coding-standards.md](./coding-standards.md)：包/类/方法命名、UI 设计系统、Git 规范。
- [testing.md](./testing.md)：测试策略与门禁。
- [deployment-guide.md](./deployment-guide.md)：构建、jlink 裁剪、NSIS 打包发布。
- [troubleshooting.md](./troubleshooting.md)：常见问题排查手册。
- [CHANGELOG.md](./CHANGELOG.md)：版本化变更记录。

> 权威链（冲突时裁决顺序）：`SRS.md`（需求基线）> `high-level-design.md` > 专项文档 > `CHANGELOG.md`。代码与文档冲突时按权威链裁决，不得反向迁就代码。