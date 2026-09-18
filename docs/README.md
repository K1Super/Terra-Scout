# Terra Scout 文档导航

> Terra Scout —— 环境感知编排平台（P0 单机桌面版）
> Electron + React 桌面 UI ⇄ HTTP（127.0.0.1 + Token 鉴权）⇄ Spring Boot 单机 Java 内核 ⇄ 内嵌 H2，零外部中间件。
> 文档版本：V3.0 ｜ 更新日期：2026-09-18

## 文档结构

| 文档 | 主题 | 状态 |
|---|---|---|
| [SRS.md](SRS.md) | 需求规格说明：范围冻结、功能/非功能需求、需求基线（D-001~D-018）、验收标准 | ✅ |
| [high-level-design.md](high-level-design.md) | 总体设计：双进程架构、模块设计、ADR、任务引擎、并发、异常、进程、安全、核心算法、目录结构 | ✅ |
| [database-design.md](database-design.md) | 数据库设计：H2 配置、单一 V1 基线 DDL、枚举取值、备份与迁移失败处理 | ✅ |
| [api-spec.md](api-spec.md) | 接口规范：通用约定、46 错误码 / 9 HTTP 族、18 类端点、幂等与分页 | ✅ |
| [getting-started.md](getting-started.md) | 快速开始：环境要求、构建启动、基本使用、开发顺序 | ✅ |
| [coding-standards.md](coding-standards.md) | 编码规范：Java / TS、状态管理分层约束、日志、UI 设计系统、页面约定、Git | ✅ |
| [testing.md](testing.md) | 测试说明：测试策略、环境与工具、测试数据、门禁与验收（含混沌测试） | ✅ |
| [deployment-guide.md](deployment-guide.md) | 部署指南：构建流程、jlink 裁剪、NSIS 打包、配置参考、依赖与许可证、发布检查清单 | ✅ |
| [troubleshooting.md](troubleshooting.md) | 常见问题排查：症状 / 根因 / 解法手册 | ✅ |
| [CHANGELOG.md](CHANGELOG.md) | 变更记录：按时间倒序的版本化变更（Keep a Changelog 风格） | ✅ |
| [assets/openapi.yaml](assets/openapi.yaml) | OpenAPI 3.0 契约（与 api-spec.md 同步维护） | ✅ |
| [assets/sdk-metadata-schema.json](assets/sdk-metadata-schema.json) | SDK 元数据 JSON Schema | ✅ |

## 推荐阅读路径

### 新人入门

1. [SRS.md](SRS.md) §1~§3 —— 理解产品定位与范围边界
2. [getting-started.md](getting-started.md) —— 跑起来
3. [high-level-design.md](high-level-design.md) §1~§2 —— 理解系统骨架

### 后端开发

1. [SRS.md](SRS.md) §6 —— 需求基线与权威决策
2. [high-level-design.md](high-level-design.md) §3~§9 —— 设计细节与核心算法
3. [database-design.md](database-design.md) + [api-spec.md](api-spec.md) —— 契约落地
4. [coding-standards.md](coding-standards.md) §2、§6 —— 编码与日志规范

### 前端开发

1. [high-level-design.md](high-level-design.md) §1、§7 —— 双进程与握手协议
2. [api-spec.md](api-spec.md) —— 接口契约
3. [coding-standards.md](coding-standards.md) §3~§5、§7~§8 —— 状态管理分层、UI 设计系统与页面约定

### 测试与发布

1. [testing.md](testing.md) —— 测试策略与门禁
2. [deployment-guide.md](deployment-guide.md) —— 构建、打包、发布清单
3. [troubleshooting.md](troubleshooting.md) + [CHANGELOG.md](CHANGELOG.md) —— 已知问题与历史决策

## 文档维护约定

- 文档间引用一律使用上表文件名（相对链接），禁止引用已废弃文件名。
- 契约类文档三表同步义务：api-spec.md ↔ assets/openapi.yaml ↔ 代码枚举（见 SRS.md 需求基线）。
- 数据库 schema 变更：只允许新增 `V<n>__*.sql`，已发布脚本永不修改（见 database-design.md §2）。
- 需求或设计变更必须同时更新 SRS.md 需求基线与本表状态，并在 CHANGELOG.md 记录。