# Terra Scout 接口规范（API Specification）

> 本文为 REST API 契约的权威定义，与 assets/openapi.yaml（OpenAPI 3.0）同步维护。
> 文档版本：V3.0 ｜ 更新日期：2026-09-18 ｜ 适用范围：P0 单机桌面版
> 定稿基线：46 错误码 / 9 HTTP 族；鉴权归 401 族；新增 409006（队列满）；
> 幂等重放返回 200 + 首次结果；已补齐 UI 全部端点。
> 决策依据见 SRS.md 需求基线（D-001 / D-002 / D-014）。

## 1. 通用约定

- 前缀：`/api/v1`
- 鉴权：Header `X-TerraScout-Token: <base64>`（失败返回 HTTP 401 + `401001`）
- 请求/响应：`application/json`
- 字符集：UTF-8
- 时间：Unix 毫秒
- ID：UUID v4
- 所有接口仅监听 127.0.0.1

## 2. 统一响应结构

```json
{
  "code": 200000,
  "message": "success",
  "data": {},
  "traceId": "uuid",
  "timestamp": 1700000000000
}
```

- 错误响应同样带信封：`code` 为 6 位业务码、`details` 可选结构化明细（如 422001 的 `selectedPath`/`candidates`/`hint`）。
- **客户端解码约定（裁决 R46）**：非 2xx 时优先透传信封业务码（校验 `code ÷ 1000 == HTTP 状态`，D-001），信封缺失或码与状态不符才回退 `HTTP×1000`；`details.hint` 存在时附加展示，保证真实错误码与可执行指引不丢失。

## 3. 标准错误码体系

采用两段式编码：`<HTTP状态码><3位业务序号>`，共 6 位纯数字。

**强约束（D-001）**：

1. **HTTP 状态码必须等于 code 前 3 位**（`HTTP status == code / 1000`），禁止错位
2. 所有成功响应 = HTTP 200 + `code: 200000`
3. **幂等重放**：相同 `idempotencyKey` 重复提交返回 `200 + 200000 + 首次结果`，不设错误码

成功码：`200000`（HTTP 200 + 000 业务序号）。

### 3.1 完整错误码表

#### 400 · 请求参数错误

| 错误码 | 含义 |
|---|---|
| 400001 | 项目路径为空或格式非法 |
| 400002 | 请求体 JSON 结构非法 |
| 400003 | 缺少必填字段 |
| 400004 | `idempotencyKey` 格式非法 |
| 400010 | AI 配置不完整或非法 |

#### 401 · 鉴权失败

| 错误码 | 含义 |
|---|---|
| 401001 | Token 缺失或校验失败 |

#### 403 · 权限与安全拒绝

| 错误码 | 含义 |
|---|---|
| 403001 | 请求来源非 127.0.0.1 |
| 403002 | 命令不在白名单，或参数包含 shell 元字符 |
| 403003 | 隔离域无写入权限 |
| 403004 | 项目根路径无读取权限 |

#### 404 · 资源不存在

| 错误码 | 含义 |
|---|---|
| 404001 | 项目根路径不存在 |
| 404002 | 任务 ID 不存在 |
| 404003 | 装配计划 ID 不存在 |

#### 409 · 状态冲突

| 错误码 | 含义 |
|---|---|
| 409001 | 同一项目已有任务在执行，进程锁获取失败 |
| 409002 | 本机已装版本与项目约束冲突，需用户决策 |
| 409003 | 隔离域 `.devenv` 被其他进程占用 |
| 409004 | 隔离域已存在且非本工具创建，拒绝覆盖 |
| 409005 | 任务已处于终态，无法再次操作 |
| 409006 | 任务队列已满，拒绝新任务 |

#### 422 · 业务校验失败

| 错误码 | 含义 |
|---|---|
| 422001 | 项目类型无法识别 |
| 422002 | 声明文件冲突：`pom.xml` 与 `package.json` 约束矛盾 |
| 422003 | 父 POM 未解析到 |
| 422004 | 属性占位符无法求值 |
| 422005 | 锁文件与声明文件不一致 |
| 422006 | 项目约束无任何 SDK 版本满足 |
| 422007 | 项目约束只能由 EOL 版本满足，已拒绝 |
| 422008 | 项目约束只能由存在高危 CVE 的版本满足，已拒绝 |
| 422009 | SHA256 校验失败 |
| 422010 | 解压中止：检测到 Zip-Slip 或符号链接 |
| 422011 | 隔离域路径超出 Windows 260 字符限制 |
| 422012 | 依赖安装失败：`mvn install` 或 `npm install` 非零退出 |
| 422013 | 隔离配置文件生成失败 |
| 422014 | 注入后 `JAVA_HOME` / `NODE_HOME` 路径不存在 |
| 422015 | 命令执行失败：非零退出或超时 |

#### 500 · 服务端内部错误

| 错误码 | 含义 |
|---|---|
| 500001 | 子进程未继承系统基础变量，环境异常 |
| 500002 | `env.ps1` 脚本生成或执行失败 |
| 500003 | 任务心跳超时，进程可能被强制终止 |
| 500004 | 回滚执行失败：安装目录或 `.devenv` 无法删除 |
| 500005 | H2 数据库文件损坏 |
| 500006 | H2 迁移脚本校验失败 |
| 500007 | 未知内部错误 |

#### 502 · 第三方服务错误

| 错误码 | 含义 |
|---|---|
| 502001 | SDK 源不可达：官方源与所有镜像均连接失败 |
| 502002 | 镜像内容与官方 checksum 不一致，疑似镜像劫持 |
| 502003 | 服务端不支持 Range，断点续传不可用 |
| 502004 | AI 服务不可用，已降级 |

#### 507 · 容量不足

| 错误码 | 含义 |
|---|---|
| 507001 | 下载目录磁盘空间不足 |
| 507002 | 解压目标磁盘空间不足 |

### 3.2 域与错误码对应关系

| 业务域 | 主要错误码 |
|---|---|
| 项目解析 | 400001、404001、422001-422005 |
| 版本匹配 | 409002、422006-422008 |
| SDK 获取 | 502001-502003、422009、422010、507001-507002 |
| 隔离装配 | 403003、409003、409004、422011-422013 |
| 运行执行 | 403002、422014、422015、500001、500002 |
| 任务调度 | 409001、409005、409006、500003 |
| 系统安全与鉴权 | 401001、403001、403004、404002、404003、500004-500007、502004 |

### 3.3 总量统计

| HTTP | 数量 |
|---|---|
| 400 | 5 |
| 401 | 1 |
| 403 | 4 |
| 404 | 3 |
| 409 | 6 |
| 422 | 15 |
| 500 | 7 |
| 502 | 4 |
| 507 | 2 |
| **合计** | **47** |

**47 个错误码，9 个 HTTP 分类，一张表管完。**

> 一句话总结：**6 位数字：前 3 位 HTTP 状态码，后 3 位业务序号；HTTP 状态码与 code 前 3 位强一致。**

## 4. 接口定义

### 4.1 项目导入分析

```http
POST /api/v1/project/analyze
```

请求：

```json
{
  "path": "D:\\workspace\\my-project",
  "idempotencyKey": "uuid (可选)"
}
```

- **有效项目根（裁决 R46）**：所选目录根无 pom.xml / package.json / go.mod / .python-version / pyproject.toml 时，若其直接子目录中**恰有一个**含声明文件，则自动采纳该子目录为项目根（`name`/`projectRootPath` 均取自采纳根）；根自身含声明文件则保持原根。
- **多语言识别（裁决 R48）**：`type` 枚举 MAVEN / NPM / GO / PYTHON / MIXED / UNKNOWN；GO 由 go.mod、PYTHON 由 .python-version / pyproject.toml 声明；两类及以上声明并存 → MIXED（约束按 JAVA → NODE → GO → PYTHON 稳定顺序输出）。PYTHON 约束优先序 `.python-version`（首行原样，剥离 UTF-8 BOM）→ pyproject.toml `[project]` 段 `requires-python` → UNKNOWN；GO 约束取 go.mod `go` 指令归一为 `>=1.21`。
- **多模块聚合（裁决 R46）**：MAVEN / MIXED 项目根 pom.xml 声明 `<modules>` 时，BFS 遍历聚合树（深度 ≤5、visited 防环）解析每个子模块 pom.xml，仅收集**已声明版本**并按标准化版本去重（根声明优先，`sourceFile` 为相对根的正斜杠路径，如 `a/pom.xml`）；缺失的模块目录跳过并告警（导入容错）；全部未声明版本时回退单条 `UNKNOWN` 约束（与单模块行为一致）。
- **NPM 引擎约束（裁决 R47）**：NODE 约束按 `.nvmrc` → `.node-version` → package.json `engines.node` 优先序提取；engines.node 保留范围表达式原样（如 `>=20.0.0`），由 SdkVersionMatcher 按主版本语义匹配（支持 `>=`/`>`/`<=`/`<`、`||` 或组合、空格 AND 组合、`20.x` 写法）；package.json 解析失败或未声明 engines 时回退 `UNKNOWN`。
- **SDK 版本选配（裁决 R48）**：`plan.sdkInstalls[i].candidates` 为候选版本表（首项 = 自动推荐，N≤5；按 已装 → LTS → CVE 少 → 版本新 → 发行版 排序），每项含 `version/lts/eol/sizeBytes/installed`；预览单条约束匹配失败仅跳过（不阻断 analyze/detail），权威校验在 execute 的 MatchVersionStep（无候选 422006 / 仅 EOL 422007 / 仅高危 CVE 422008）。候选表取自官方目录的**完整受支持版本线**（见 §4.12「版本覆盖窗口」，裁决 R49）：Python 每条受支持 minor 线最新两个稳定 patch、Node 支持期内的全部 LTS 线与最新偶数 Current 线各最新两个、Go 最新两个 minor 线各最新两个；已 EOL 的旧线不在目录，锁定版本所在线已 EOL 时表现为无候选（最终 422006）。
- **422001 明细**：无候选或多候选（无法判别归属）时返回 422001，`details` 携带 `selectedPath`（用户所选目录）、`candidates`（含声明文件的子目录列表）、`hint`（可执行指引：请选择含 pom.xml / package.json / go.mod / .python-version 的目录）。

响应（`data` 含项目画像与装配计划）：

```json
{
  "code": 200000,
  "data": {
    "projectId": "uuid",
    "name": "my-project",
    "type": "MAVEN",
    "osType": "WINDOWS",
    "constraints": [
      { "language": "JAVA", "constraint": "17", "sourceFile": "pom.xml" },
      { "language": "NODE", "constraint": "18", "sourceFile": ".nvmrc" },
      { "language": "GO", "constraint": ">=1.21", "sourceFile": "go.mod" },
      { "language": "PYTHON", "constraint": "3.11.9", "sourceFile": ".python-version" }
    ],
    "plan": {
      "planId": "uuid",
      "sdkInstalls": [
        {
          "language": "JAVA",
          "version": "17.0.9",
          "action": "INSTALL",
          "estimatedSizeBytes": 193000000,
          "reason": "17.0.9 为 LTS 版本，满足项目约束 17",
          "candidates": [
            { "version": "17.0.9", "lts": true, "eol": false, "sizeBytes": 193000000, "installed": false },
            { "version": "17.0.13", "lts": true, "eol": false, "sizeBytes": 195000000, "installed": false }
          ]
        },
        { "language": "NODE", "version": "22.20.0", "action": "INSTALL", "candidates": [] }
      ],
      "dependencyInstalls": [
        { "ecosystem": "maven", "isolation": ".devenv/m2" },
        { "ecosystem": "npm", "isolation": ".devenv/npm-cache" }
      ],
      "verifyCommands": [
        { "command": "mvn", "args": ["-q", "compile"] }
      ]
    }
  },
  "traceId": "uuid",
  "timestamp": 1700000000000
}
```

### 4.2 项目列表

```http
GET /api/v1/project?page=1&size=20
```

响应：

```json
{
  "code": 200000,
  "data": {
    "items": [
      {
        "projectId": "uuid",
        "name": "my-project",
        "projectRootPath": "D:\\workspace\\my-project",
        "type": "MAVEN",
        "osType": "WINDOWS",
        "lastTaskStatus": "SUCCESS",
        "createdAt": 1700000000000,
        "updatedAt": 1700000005000
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

### 4.3 项目详情

```http
GET /api/v1/project/{projectId}
```

响应（裁决 R47/R48）：`data` 含 `projectId`、`name`、`projectRootPath`、`type`（项目类型，自 profile_json 恢复；旧版纯约束数组记录回退磁盘重检）、`osType`、`constraints`（同 4.1 结构，稳定性约定 JAVA→NODE→GO→PYTHON）、`plan`（预览装配计划：`planId`=projectId，`sdkInstalls` 按约束实时匹配并携带 `candidates` 候选表（首项 = 推荐，裁决 R48）——单条匹配失败仅跳过不阻断详情，权威校验在 execute 的 MatchVersionStep）、`createdAt`、`updatedAt`。用于 UI 重启后恢复项目详情页。

```json
{
  "code": 200000,
  "data": {
    "projectId": "uuid",
    "name": "Tests",
    "projectRootPath": "C:\\Users\\Administrator\\Desktop\\Tests",
    "type": "NPM",
    "osType": "WINDOWS",
    "constraints": [
      { "language": "NODE", "constraint": ">=20.0.0", "sourceFile": "package.json" }
    ],
    "plan": {
      "planId": "uuid",
      "sdkInstalls": [
        { "language": "NODE", "version": "20.11.0", "action": "INSTALL" }
      ],
      "dependencyInstalls": [],
      "verifyCommands": []
    },
    "createdAt": 1700000000000,
    "updatedAt": 1700000005000
  }
}
```

### 4.4 删除项目

```http
DELETE /api/v1/project/{projectId}
```

- 语义（D-014）：**仅删除数据库记录与装配关联，不触碰磁盘 `.devenv` 与任何用户文件**
- 响应：`200 + 200000 + data: null`
- 重新导入同路径项目即恢复记录（UI 的"撤销删除"由此实现）

### 4.5 执行装配

```http
POST /api/v1/task/execute
```

请求（裁决 R48：`versionOverrides` 可选，携带用户选配的 SDK 版本覆盖）：

```json
{
  "planId": "uuid",
  "confirm": true,
  "idempotencyKey": "uuid",
  "versionOverrides": [
    { "language": "GO", "version": "1.24.2" }
  ]
}
```

- **版本覆盖校验（裁决 R48）**：`versionOverrides` 每项在 MatchVersionStep 经 `SdkVersionMatcher.overrideVersion` 同源校验——所选版本必须存在于候选集内（可用、非 EOL、无 CRITICAL CVE 且满足项目约束），否则该任务在 MatchVersionStep 失败（步骤错误 422006）并回滚为 ROLLED_BACK；execute 本身仍按 P0 异步阈值返回 200 + QUEUED + taskId。省略某项即采用自动推荐版本。已装版本被选中时复用（REUSE），不触发下载安装。

响应（D-002：异步语义由 `taskId` + 轮询表达，统一返回 200）：

```json
{
  "code": 200000,
  "data": {
    "taskId": "uuid",
    "status": "QUEUED"
  }
}
```

### 4.6 任务列表

```http
GET /api/v1/task?status=RUNNING&page=1&size=20
```

- `status` 可选，过滤任务状态
- 响应：`data.items` 含 `taskId`、`type`、`status`、`progress`、`errorCode`、`errorMsg`、`createdAt`、`updatedAt`；`data.total/page/size`

### 4.7 查询任务

```http
GET /api/v1/task/{taskId}
```

响应：

```json
{
  "code": 200000,
  "data": {
    "taskId": "uuid",
    "type": "PROJECT_ASSEMBLE",
    "status": "RUNNING",
    "progress": 0.45,
    "steps": [
      { "index": 0, "name": "INSTALL_SDK_JAVA", "status": "SUCCESS" },
      { "index": 1, "name": "INSTALL_SDK_NODE", "status": "RUNNING" },
      { "index": 2, "name": "ISOLATE_PROJECT", "status": "PENDING" }
    ],
    "errorCode": null,
    "errorMsg": null,
    "retryCount": 0,
    "maxRetry": 3,
    "createdAt": 1700000000000,
    "updatedAt": 1700000005000
  }
}
```

### 4.8 任务日志

```http
GET /api/v1/task/{taskId}/logs?stepIndex=1&tail=200
```

- `stepIndex` 可选（缺省为当前执行步骤）；`tail` 默认 200 行
- 响应：`data` 含 `taskId`、`stepIndex`、`lines[]`、`truncated`、`stdoutFile`、`stderrFile`（路径已脱敏）
- 数据来源：`command_execution.stdout_file` / `stderr_file`

### 4.9 任务操作

```http
POST /api/v1/task/pause
POST /api/v1/task/resume
POST /api/v1/task/cancel
```

请求：

```json
{ "taskId": "uuid" }
```

- 终态任务操作返回 409005；锁冲突返回 409001

### 4.10 SDK 版本列表

```http
GET /api/v1/sdk/list?language=JAVA&os=WINDOWS&arch=AMD64
```

- `items` 统一按版本号数字段**升序排布**（低版本 → 高版本），catalog 与系统合成项同规则
- 过老 JAVA（major < 11，如 1.8.0_504）不返回（catalog 过滤 + 系统探测合成过滤）
- 目录条目 `releaseTime` 齐全（NODE=nodejs.org、PYTHON=python.org 官方 downloads API（跳过 pre_release 条目）、GO=GitHub tags 提交日期）；系统探测合成项（`systemInstalled: true`，本机已装但不在目录的版本）仅含 `language/version/os/arch/installed/systemInstalled/installedPath`，不含目录字段
- **空间去向透明（红线）**：每条目录条目必含 `installPath`——已装=记录实际物理路径（含 R45 自定义落位），未装=安装目的路径（`{data-root}\sdks\{language}\{version}`），安装前即可知晓落位；`releaseTime` 仍随 API 返回但**前端不再展示**（裁决 R44）

响应（已装条目含 `recordId` / `installedPath`，全条目含 `installPath`）：

```json
{
  "code": 200000,
  "data": {
    "items": [
      {
        "language": "JAVA",
        "version": "17.0.9",
        "os": "WINDOWS",
        "arch": "AMD64",
        "downloadUrl": "https://...",
        "sha256": "...",
        "lts": true,
        "eol": false,
        "releaseTime": 1700000000000,
        "installed": true,
        "recordId": "uuid",
        "installedPath": "C:\\Users\\user\\.terrascout\\sdks\\java\\17.0.9",
        "installPath": "C:\\Users\\user\\.terrascout\\sdks\\java\\17.0.9"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

### 4.11 SDK 安装 / 卸载 / 取消

```http
POST /api/v1/sdk/install
POST /api/v1/sdk/uninstall
POST /api/v1/sdk/install/{jobId}/cancel
```

安装请求（`recordId` 由 4.10 列表提供）：

```json
{
  "language": "JAVA",
  "version": "17.0.9",
  "scope": "PROJECT",
  "projectId": "uuid",
  "idempotencyKey": "uuid",
  "installDir": "D:\\sdks"
}
```

卸载请求：

```json
{
  "recordId": "uuid",
  "idempotencyKey": "uuid"
}
```

- `scope` 表达记录归属（PROJECT = 由项目装配触发），物理安装位置统一在 SDK 仓库（D-004）；`installDir` 可选打开自定义落位（见下）
- **自定义安装目录（裁决 R45）**：`installDir` 可选（绝对路径字符串），作为仓库根**自动创建** `{installDir}\{语言小写}\{版本}` 两级结构落位，避免混放；缺省回落标准仓库 `{data-root}\sdks\{language}\{version}`。非绝对路径返回 `400001`；所选根目录无法创建/不可写返回 `400001`；目标 `{语言}\{版本}` 目录已存在（防覆盖混乱）返回 `409004`。已装版本复用分支不校验 `installDir`（复用语义不变）。成功解压后在安装目录内写落位票根 `.terrascout-sdk-marker`（含 language/version/home；写失败仅告警不阻断）
- **安装异步化**：未装版本立即返回 `data.jobId` + `status: "QUEUED"` + `data.installPath`（落地路径任务注册即确定，空间去向透明）；后台执行真实下载 + SHA-256 校验 + 解压 + 写安装记录，进度经 4.18 轮询；已装版本复用同步返回 `data.recordId` + `status: "SUCCESS"`（无 `jobId`）
- 失败错误：`409006 TASK_QUEUE_FULL`（队列满）；队列容量与进度保留策略见 4.18

**安装取消（R43 红线）**：`POST /sdk/install/{jobId}/cancel`——置取消信号并立即返回快照（`stage: "CANCELLING"`）；工作线程在下载块 / 阶段边界中止，**即时清理**下载暂存（`.part`）与半解压目录后收敛为终态 `stage: "CANCELLED"`（`finished=true`、`success=false`、`cancelled=true`）。排队期取消直接收敛、不产生任何文件。错误：未知 `jobId` 返回 `404002 TASK_NOT_FOUND`；任务已终态返回 `409005 TASK_TERMINAL_STATE`。

**卸载物理回收（R43 红线，R45 扩展）**：卸载在删除记录前先物理删除 SDK 目录——引用计数保护（同语言+版本仍有其他成功安装记录的共享目录保留）；删除前先原子重命名 `.trash-<uuid>` 再递归删除（中途失败可被下次启动扫尾 `sweepStaleTrash`）；目录被占用时删除失败抛 `500004 ROLLBACK_FAILED`、事务回滚**记录保留**（重试可恢复）。响应含 `data.directoryRemoved`（本次是否物理删除）与 `data.installPath`。物理删除护栏：记录路径 = 标准落位，或目录内存在落位票根 `.terrascout-sdk-marker`（R45 自定义安装产物）时才删除；其余路径仅删记录（WARN）。

### 4.12 SDK 元数据重载（官方源在线刷新）

```http
POST /api/v1/sdk/metadata/reload
```

刷新流程（D-009）：官方源在线拉取（JAVA=api.adoptium.net，仅收录 major ≥ 11，淘汰 Java 8 等过老版本；NODE=nodejs.org（npmmirror 镜像兜底）；PYTHON=python.org（发布日期取官方 downloads API 全量数组，跳过 pre_release，DRF 分页形态兜底）；GO=go.dev（索引不可达回退境内镜像 golang.google.cn，下载地址固定 dl.google.com，发布日期取 GitHub `golang/go` tags 提交日期；任一日期源失败仅降级该字段、不阻断主流程））→ 按语言以 `(language, version, os, arch)` 唯一键 upsert 进 `sdk_version`（增改；来源条目无日期时**不回退覆盖**已有日期）→ 淘汰过老 JAVA 行（major < 11 物理删除，条数见响应 `purgedStaleJava`）→ 全部语言 0 条时回退本地外部文件 `{data-root}/config/sdk-metadata.json` → 两者皆不可用返回 502001 → 成功后将库内全量元数据反向写回外部文件（schemaVersion 1.0，source=official-refresh）。

- **版本覆盖窗口（裁决 R48 初版，R49 升级为完整线覆盖）**：抓取器按「版本线」策略覆盖**完整受支持版本线**而非仅最新窗口——PYTHON 按 major.minor 归组，行级 EOL 静态表（官方 PEP 发布计划：3.9→2025-10-31、3.10→2026-10-31、3.11→2027-10-31、3.12→2028-10-31、3.13→2029-10-31、3.14→2030-10-31）排除已停维的行（表外新行如 3.15 视为未来受支持行），每条受支持线按版本倒序至多探测 16 个（安全维护期「仅源码」发布无 windows 元数据时顺延深挖，如 3.11 线的 3.11.10~3.11.16 均为源码发布、直到 3.11.9/3.11.8 才有 Windows 产物）、保留该线最近 2 个可安装稳定 patch；官方从未提供 windows 元数据的线（如 3.10 全系仅源码）不产生候选——不破坏「下载→SHA-256 校验」闭环；NODE 按 major 归组，行级支持性按官方发布计划 EOL 静态表裁决（12→2022-04-30、14→2023-04-30、16→2023-09-11、18→2025-04-30、20→2026-04-30、22→2027-04-30、24→2028-04-30、26→2029-04-30；index.json 不含 end 字段，不能从数据内取）——行内存在 LTS 条目时：表内 major 已过 EOL 即整线排除（如 18/20）、表上界之外的更大 major 视为未来受支持线、表下界以下不在表内的远古 major（6/8/10 等）排除；无 LTS 条目的偶数 major 且大于所有 LTS 行的 major 视为 Current 行纳入（奇数 non-LTS 行排除），每线保留最新 2 个（SHASUMS 缺失顺延）；GO 仅收录最新 2 个 minor 线（官方仅维护最近两大版本，如 1.26/1.27），每线保留最新 2 个 stable；JAVA 维持 major≥11 的 LTS/feature 线。因 upsert 不删除、旧条目保留，元数据表为「历次刷新窗口的累积集」，**不含已 EOL 版本线**。影响：锁定版本所在线已 EOL（如 Python 3.9.x、Node 18/20、Go 1.24 及更早）或不在支持窗口时，无候选会在 `MATCH_VERSION` 步以 422006 拦截（先于安装，无下载）。

响应：

```json
{
  "code": 200000,
  "data": {
    "loaded": true,
    "sdkCount": 23,
    "updatedAt": "2026-09-17T06:35:12Z",
    "purgedStaleJava": 0,
    "sources": [
      { "language": "JAVA", "status": "SUCCESS", "count": 5 },
      { "language": "NODE", "status": "SUCCESS", "count": 6 },
      { "language": "PYTHON", "status": "SUCCESS", "count": 8 },
      { "language": "GO", "status": "SUCCESS", "count": 4 }
    ]
  }
}
```

- `data.purgedStaleJava`：本次刷新淘汰的过老 JAVA 版本条数（major < 11，如 1.8.0_504，物理删除）
- `data.sources[].status`：`SUCCESS`（本语言源可达并完成 upsert，`count` 为处理条数；可达但无匹配产物亦为 SUCCESS，count=0）或 `FAILED`（本语言源不可达，`message` 记录失败原因，`count` 恒为 0）。单语言失败不阻断其他语言，也不使整体请求失败
- `data.fallback` 仅当官方源全部 0 条、回退本地外部文件成功时出现，取值 `LOCAL_FILE`
- `data.updatedAt` 为刷新完成时刻（ISO-8601）
- 错误：`502001 SDK_SOURCE_UNREACHABLE`（官方源与所有镜像拉取失败，且本地元数据文件不存在、为空或全部条目非法）

### 4.13 环境脚本

```http
GET /api/v1/env/script/{projectId}
```

响应：

```json
{
  "code": 200000,
  "data": {
    "osType": "WINDOWS",
    "script": "$env:JAVA_HOME='...'\n$env:PATH='...'",
    "scriptPath": "D:\\workspace\\my-project\\.devenv\\env.ps1"
  }
}
```

### 4.14 审计日志查询

```http
GET /api/v1/audit?bizId=uuid&action=TASK_CREATE&page=1&size=20
```

- `bizId` / `action` 可选过滤
- 响应：`data.items` 含 `seq`、`bizId`、`action`、`targetType`、`targetId`、`result`、`createdAt`；`data.chainValid`（bool，hash 链校验结果）

### 4.15 设置读取与更新

```http
GET /api/v1/settings
PUT /api/v1/settings
POST /api/v1/settings/ai/test
```

请求/响应体（AI 配置并入本端点，含供应商标准模板与自建覆盖；`aiApiKey` 仅本地持久化，诊断包与日志一律掩码）：

```json
{
  "download": { "mirror": "https://repo.huaweicloud.com", "timeoutMs": 60000, "maxRetry": 3 },
  "commandTimeoutMs": 600000,
  "logLevel": "INFO",
  "aiEnabled": false,
  "aiProvider": "deepseek",
  "aiBaseUrl": "https://api.deepseek.com",
  "aiModel": "deepseek-chat",
  "aiApiKey": ""
}
```

- `aiProvider` 取值 `deepseek` / `glm` / `openai-compatible`（标准模板：默认接入地址 + 推荐模型，均可在保存时覆盖；非法供应商忽略并回落默认，`aiBaseUrl` 尾部斜杠自动去除、空值回落模板默认）
- 持久化至 `user-settings.json`；仅接受白名单键
- 热生效：镜像 / 超时 / 重试 / 日志级别 / AI 开关与配置；线程池与心跳参数需重启（见 deployment-guide.md 配置参考）
- `POST /settings/ai/test`：对提交的整段 AI 配置（不落盘）发起一次最小真实对话补全探测；入参不完整或非法（供应商不在模板集 / API Key 为空）→ `400010 AI_CONFIG_INVALID`；探测结果统一 `200 + 200000`，`data` 为 `{ ok, message, latencyMs }`（探测失败属预期结果，非业务异常）

### 4.16 系统信息 / 备份 / 诊断包

```http
GET /api/v1/system/info
POST /api/v1/system/backup
GET /api/v1/system/diagnostic
```

- `info`：`data` 含 `version`、`javaVersion`、`dataDir`、`sdkRepoDir`、`dbSizeBytes`；R51 起路径字段返回**完整真实路径**（与 SDK 列表 installedPath 一致，本机 UI 展示无脱敏）
- `backup`：立即执行 H2 `BACKUP TO`，`data` 含 `backupFile` 完整路径
- `diagnostic`：导出脱敏诊断 ZIP 至 `{data-root}/diagnostics/`（ZIP 内容由 DiagnosticService 脱敏），`data` 含 `zipPath` 完整落盘路径

### 4.17 健康检查

```http
GET /api/v1/health
```

- **唯一免鉴权端点**；`data` 含 `status: "UP"`

### 4.18 SDK 安装进度查询

```http
GET /api/v1/sdk/install/{jobId}
```

轮询 4.11 异步安装任务的实时快照（前端 600ms 轮询，`finished` 后停止）：

```json
{
  "code": 200000,
  "data": {
    "jobId": "uuid",
    "language": "JAVA",
    "version": "17.0.9",
    "stage": "DOWNLOADING",
    "message": "正在下载 JAVA 17.0.9",
    "percent": 46,
    "totalBytes": 192000000,
    "bytesDownloaded": 88320000,
    "finished": false,
    "success": false,
    "cancelled": false,
    "recordId": null,
    "installPath": "C:\\Users\\user\\.terrascout\\sdks\\java\\17.0.9",
    "error": null
  }
}
```

- `stage` 阶段常量：`QUEUED` → `STARTING`（校验环境）→ `DOWNLOADING`（下载中，`percent` 随字节进度封顶 99）→ `EXTRACTING`（解压）→ `FINISHING`（写入安装记录）→ `DONE`（成功，`percent=100`、`finished=true`、`success=true`，附 `recordId`）或 `FAILED`（`finished=true`、`success=false`，`error` 记录失败原因）或取消链路 `CANCELLING`（用户请求取消，工作线程清理中）→ `CANCELLED`（`finished=true`、`success=false`、`cancelled=true`，`.part` 与半解压残留已即时清理）
- `installPath`：任务注册即确定的落地路径（标准仓库或 R45 自定义目录，全程可见）；`cancelled`：取消终态的收敛标志（前端据此走「已取消」轻提示而非错误弹窗）
- `percent` 口径：`DOWNLOADING` 期间按 `bytesDownloaded / totalBytes` 计算；官方源以 chunked 传输、无 Content-Length 时（实测 dl.google.com），`totalBytes` 回退为目录元数据 `sizeBytes` 估算值（真实长度已知时优先真实值），保证百分比持续推进；两者均未知时 `percent` 保持 0、`bytesDownloaded` 仍如实上报
- 快照仅驻留内存：进程内最多保留 32 个任务，超出淘汰最旧；任务完成后保留快照供前端终态收尾，随后可被淘汰
- 未知 `jobId` 返回 `404002 TASK_NOT_FOUND`

## 5. 幂等

- 所有写操作支持 `idempotencyKey`（UUID）
- 相同 key 重复提交：返回 `200 + 200000 + 首次结果`（含原 `taskId`），**不返回错误码**
- 存储于 `task.idempotency_key`，唯一约束；键格式非法返回 400004

## 6. 分页

- 参数：`page`（默认 1）、`size`（默认 20，最大 100）
- 响应：`items`、`total`、`page`、`size`

## 7. 超时与取消

- 默认超时 30s
- 长任务返回 `taskId`，通过轮询查询（UI 每 2 秒）
- P0 用轮询，P1 可改 SSE