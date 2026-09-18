# Terra Scout

> 环境感知编排平台 · P0 单机桌面版

Terra Scout 是一款 **环境感知编排平台**：自动识别导入项目的语言与版本约束，按需安装/匹配隔离的 SDK（Java / Python / Node.js / Go），在隔离环境中编排执行依赖安装与任务，零外部中间件、开箱即用。

```text
Electron + React 桌面 UI ⇄ HTTP（127.0.0.1 + Token 鉴权）⇄ Spring Boot 单机 Java 内核 ⇄ 内嵌 H2
```

## 核心特性

- **项目导入感知**：递归解析 Maven 父子 POM / `package.json`（.nvmrc、engines.node 优先级匹配），自动识别类型与版本约束
- **SDK 生命周期管理**：版本目录聚合（官方 API + EOL 过滤，覆盖完整支持的版本线）、下载 SHA-256 校验、断点续传、可取消安装即清理、卸载物理回收
- **任务编排执行**：`QUEUED → RUNNING → SUCCESS/FAILED` 九步任务流水线，心跳自上报，失败自动重试（MAX_RETRY=3）
- **数据生成（AI）**：接入主流 AI API（DeepSeek、GLM 等）生成测试数据，支持配置面板，生成结果可视化
- **安全与审计**：127.0.0.1 单机绑定 + Token 鉴权、命令白名单正则、全链路操作审计、诊断包导出
- **质量门禁**：Maven verify 内置 Checkstyle + 438+ 单元测试 + JaCoCo 覆盖率 ≥ 80% 三重门禁

## 技术栈

| 层 | 技术 |
|---|---|
| 桌面壳 | Electron 30 + React 18 + TypeScript + Ant Design 5 + Vite |
| 状态管理 | TanStack Query（服务端数据） + Zustand（客户端进程状态），严格分层不交叉 |
| 内核 | Java 17 + Spring Boot 3.2 + H2（Flyway 单一 V1 基线） |
| 构建 | Maven 3.9 多模块（core → parser → download → task → env → app）+ npm |

## 目录结构

```text
Terra Scout/
├── terra-scout-core/       # 领域模型与公共基础设施
├── terra-scout-parser/     # 项目清单解析（Maven POM / package.json / Python / Go）
├── terra-scout-download/   # 下载器（断点续传、SHA-256、可取消）
├── terra-scout-task/       # 任务引擎（状态机、心跳、重试、锁）
├── terra-scout-env/        # 环境绑定与隔离
├── terra-scout-app/        # Spring Boot 内核入口（REST API 全量）
├── terra-scout-electron/   # Electron 桌面端（UI + 进程握手 + 打包）
├── config/checkstyle/      # Checkstyle 规范
├── docs/                   # 需求/设计/接口/测试/部署全套文档
└── .github/workflows/      # CI 门禁流水线
```

## 快速开始

前置要求：JDK 17+、Maven 3.9+、Node.js 20+

```bash
# 1. 构建并测试内核（含 Checkstyle + JaCoCo 覆盖率门禁）
mvn clean verify

# 2. 启动桌面端（开发模式）
cd terra-scout-electron
npm install
npm run dev
```

> 内核启动后通过 stdout `READY <port>` 与 Electron 握手；运行时数据（H2 / 日志 / SDK 仓库）默认位于 `~/.terrascout`，首启自动生成 H2 密码并持久化。

打包发布：`cd terra-scout-electron && npm run build && npm run package`（NSIS 安装包，详见 [deployment-guide.md](docs/deployment-guide.md)）。

## 文档导航

| 文档 | 主题 |
|---|---|
| [docs/SRS.md](docs/SRS.md) | 需求规格说明与需求基线（D-001~D-018） |
| [docs/high-level-design.md](docs/high-level-design.md) | 总体设计：双进程架构、ADR、核心算法 |
| [docs/database-design.md](docs/database-design.md) | H2 设计、V1 基线 DDL、备份与迁移 |
| [docs/api-spec.md](docs/api-spec.md) | REST 契约：错误码族、端点、幂等与分页 |
| [docs/getting-started.md](docs/getting-started.md) | 环境要求、构建启动、基本使用 |
| [docs/coding-standards.md](docs/coding-standards.md) | Java / TS 规范、状态管理分层、UI 设计系统 |
| [docs/testing.md](docs/testing.md) | 测试策略、门禁与验收（含混沌测试） |
| [docs/deployment-guide.md](docs/deployment-guide.md) | 构建、jlink 裁剪、NSIS 打包、发布清单 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 常见问题排查手册 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 版本化变更记录 |

## 工程质量

- 提交规范：`<type>(<scope>): <subject>`（feat/fix/docs/test/refactor/chore…）
- 分支策略：`main`（生产）/ `develop`（开发）/ `feature/*` / `hotfix/*` / `release/*`
- CI 门禁（`.github/workflows/ci.yml`）：后端 `mvn verify`（Checkstyle + 测试 + 覆盖率 ≥ 80%）+ 前端 typecheck / 测试 / 构建

## 许可证

[MIT License](./LICENSE)