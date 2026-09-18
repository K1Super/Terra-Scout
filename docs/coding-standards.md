# Terra Scout 编码规范

> 文档版本 V3.0 ｜ 更新日期 2026-09-18 ｜ 适用范围 P0

---

## 1. 总则

本规范统一「Terra Scout 环境感知编排平台」的服务端（Java / Spring Boot）与客户端（TypeScript / React / Electron）的编码、状态管理、API、日志、视觉与交互约定，作为 P0 范围内一切代码评审与验收的统一依据。

**约束等级：**

| 等级 | 含义 | 执行 |
|---|---|---|
| 强制 | 必须遵守的硬性条款 | 违反即阻断合入 |
| 建议 | 强烈推荐遵循 | 偏离需说明理由 |

> **权威性裁决**：视觉与颜色条款以已实现的 `src/renderer/styles/tokens.css` + `src/renderer/theme.ts`（单色编辑部设计系统）为唯一权威。历史文档《UI Development Specification.md》中的蓝色主色、纯黑、旧缓动曲线等过时条款一律作废，不再沿用。

---

## 2. 后端 Java 规范

### 2.1 包命名

```text
com.terrascout.orchestrator.<module>.<layer>
```

模块：`config`、`controller`、`service`、`domain`、`analyzer`、`sdk`、`task`、`env`、`isolator`、`audit`、`ai`、`util`。

### 2.2 类命名

| 类型 | 规则 | 示例 |
|---|---|---|
| Controller | `XxxController` | `ProjectController` |
| Service | `XxxService` | `ProjectService` |
| Repository | `XxxRepository` | `TaskRepository` |
| Entity | 业务名（core/domain 领域模型与 DDL 表名一致） | `Task` |
| DTO | `XxxRequest` / `XxxResponse` | `AnalyzeRequest` |
| 异常 | `XxxException` | `TerraScoutException` |
| 枚举 | `XxxEnum` | `TaskStatusEnum` |
| 工具 | `XxxUtil` | `PathUtil` |
| 配置 | `XxxConfig` | `ThreadPoolConfig` |
| 测试 | `XxxTest` | `PomParserTest` |

### 2.3 方法命名

| 操作 | 前缀 | 示例 |
|---|---|---|
| 查询 | `get` / `find` / `list` | `findById` |
| 创建 | `create` / `save` | `createTask` |
| 更新 | `update` | `updateStatus` |
| 删除 | `delete` / `remove` | `deleteById` |
| 判断 | `is` / `has` / `can` | `isRunning` |
| 转换 | `to` / `from` | `toEntity` |

### 2.4 分层与依赖注入

- 分层自上而下：`controller` → `service` → `repository` → `domain`；跨层只经由 Service 接口与 Repository，Controller 不直接触达领域对象与数据访问。
- **依赖注入（强制）**：一律构造器注入（`final` 字段 + 构造器赋值），禁用字段级 `@Autowired`。
- 组件注册：`@Service` / `@Repository` / `@Component` 显式标注；拦截器、配置类放入 `config` / `interceptor` / `security` 轻量包。
- 日志声明：`private static final Logger LOG = LoggerFactory.getLogger(Xxx.class);`（SLF4J）。

```java
@Service
public class AuditService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }
}
```

### 2.5 异常使用

```java
// 业务异常：抛出 TerraScoutException（携带统一错误码）
throw new TerraScoutException(TerraScoutError.PARENT_POM_MISSING, details);

// 系统异常：包装后抛出，保留原始根因
try {
    // ...
} catch (IOException e) {
    throw new TerraScoutException(TerraScoutError.UNKNOWN, e.getMessage(), e);
}
```

- 所有对外错误统一走错误码体系（`<HTTP 状态码><3 位业务序号>`，见 api-spec / high-level-design §6），不在异常中暴露堆栈与文件绝对路径。

---

## 3. 前端 TypeScript / React 规范

### 3.1 技术栈

| 层 | 选型 |
|---|---|
| 框架 | React 18 + TypeScript（严格模式） |
| 组件库 | Ant Design 5（仅基础组件，按需引入） |
| 样式 | CSS Modules + CSS 变量（tokens.css） |
| 图标 | Lucide React（只用一个库，描边 1.5px 或 2px 全局统一） |
| 编辑器 | Monaco Editor（懒加载） |
| 状态 | Zustand（仅客户端进程状态，见 §4） |
| 请求 | TanStack Query（全部服务端数据，见 §4） |
| 路由 | React Router 6 |
| 构建 | Vite |

### 3.2 目录结构（renderer/src）

```text
styles/   tokens.css · reset.css · global.css
components/  Button·Table·Input·Dialog·LogPanel·TaskProgress·ErrorState·EmptyState·KernelStatus…
pages/    Home·ProjectDetail·Tasks·TaskDetail·Sdks·Settings·About（路由级懒加载）
hooks/    useTaskPolling.ts · useKeyboard.ts
api/      client.ts · project.ts · task.ts · sdk.ts · errorMap.ts · types.ts
store/    kernel.ts
lib/      id.ts
App.tsx · index.tsx · theme.ts
```

### 3.3 命名与代码约束（强制）

- 组件/页面 PascalCase（如 `ProjectCard.tsx`、`Sdks.tsx`），CSS Modules 与组件同名（`project-card.module.css`）。
- 生产级 TypeScript 严格模式；接口定义与 docs/api-spec.md 对齐（见 `api/types.ts`）。
- **所有组件引用设计令牌**（颜色 / 间距 / 圆角 / 阴影），禁止硬编码 hex、px 间距、圆角、阴影。
- 每个页面实现加载 / 空 / 错误 / 部分 / 正常五种状态。
- 所有交互元素键盘可达；焦点样式可见；对话框含 `role="dialog"` + `aria-modal="true"` + 焦点陷阱 + Esc 关闭 + 焦点返回；动态内容用 `aria-live="polite"`。
- 动效 ≤ 300ms，只动画 `transform` 与 `opacity`；进度条用 `transform: scaleX`（禁用 `width`）。
- Monaco 与 AntD 按需引入 + 路由级懒加载。

---

## 4. 前端状态管理与数据流分层（强制）

采用「分工明确」的双库策略：**Zustand 只承载客户端进程状态，TanStack Query 承载全部服务端数据与变更**。物理文件 `store/kernel.ts` + `index.tsx`（`QueryClientProvider`）。

| 维度 | Zustand（store/kernel.ts） | TanStack Query（页面查询层） |
|---|---|---|
| 职责 | 内核就绪/崩溃/giveup/startup-failed 状态 | 一切服务端数据：project/task/sdk/settings/system |
| 读写方式 | `setReady`/`setError`/`clearEvent` | `useQuery` 读（含 `refetchInterval` 轮询）/ `useMutation` 写 |
| 状态来源 | 主进程 `window.kernel.onEvent` 推送（非 HTTP） | HTTP（127.0.0.1 + Token）拉取 |
| 变更生效 | 渲染进程即时，不入服务端缓存 | `onSuccess` 后 `invalidateQueries` / 乐观更新（P1） |

**硬性条款：**

1. Zustand 只管理客户端进程状态 `kernel`：`ready` / `crashed` / `giveup` / `startup-failed`（`KernelEventKind = 'crashed' | 'giveup' | 'startup-failed'`），由 `window.kernel.onEvent` 推送；`ready` 由内核就绪回调写入。
2. TanStack Query 负责全部服务端数据：7 个页面（首页 / 项目详情 / 任务中心 / 任务详情 / SDK 管理 / 设置 / 关于）的 `useQuery`/`useMutation`；任务中心与任务详情的运行中任务用 `refetchInterval: 2000` 轮询，无 active 任务切 `false` 停机。
3. **两者代码不得交叉**：Zustand store 内不得存放服务端列表/明细；react-query 不得持有进程横幅状态。KernelStatus 胶囊只读 Zustand store。
4. QueryClient 默认：`retry: 1` / `staleTime: 5s` / `refetchOnWindowFocus: false`。
5. SDK 异步安装进度快照单独轮询（`refetchInterval: 600ms`，`finished` 后停止）。

---

## 5. API 客户端约束

自研 API client 只保留如下职责，**无缓存、无装载态管理**（后者全部交给 TanStack Query）：

- **传输**：纯 `fetch`，注入 `Content-Type: application/json` 与 `X-TerraScout-Token`。
- **信封解码**：统一响应 `ApiEnvelope { code, data, message, traceId, timestamp }`；成功 `code === 200000`；**HTTP 状态码必须等于 `code / 1000`**（非 2xx → `code = HTTP×1000`）。
- **401001 强制重启**：鉴权失败（401 + `401001`）由 `shouldForceRestart` 判定，路由到 `window.kernel.restart()` 提示重启。
- 契约细节：前缀 `/api/v1`、仅监听 `127.0.0.1`、`application/json`、UTF-8、时间 Unix 毫秒、ID UUID v4、写操作支持 `idempotencyKey`（幂等重放返回 `200 + 200000 + 首次结果`）。

```ts
// 纯函数信封解码（供单测）
export function decodeEnvelope<T>(status: number, json: ApiEnvelope<T> | null, rawText = ''): T | null {
  if (status < 200 || status >= 300) throw new ApiError(status * 1000, json?.message || rawText.slice(0, 200));
  if (!json) throw new ApiError(status * 1000, '响应为空');
  if (json.code !== 200000) throw new ApiError(json.code, json.message);
  return json.data === undefined ? null : json.data;
}
```

---

## 6. 日志规范

### 6.1 日志级别

| 级别 | 用途 |
|---|---|
| ERROR | 系统错误、任务失败、异常 |
| WARN | 可恢复错误、重试、降级 |
| INFO | 任务状态变更、关键操作 |
| DEBUG | 详细流程、参数、中间结果 |
| TRACE | 仅开发调试 |

### 6.2 日志格式

```text
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n
```

### 6.3 日志文件

```yaml
logging:
  file:
    name: ${terrascout.data-dir}/logs/terrascout.log
  logback:
    rollingpolicy:
      max-file-size: 10MB
      max-history: 7
      total-size-cap: 100MB
```

### 6.4 日志脱敏（强制）

```java
public class LogSanitizer {
    public static String sanitizePath(String path) {
        // D:\work\project\secret\file.txt → ***\***\secret\file.txt
        String[] parts = path.split("\\\\");
        if (parts.length <= 2) return path;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 2; i++) sb.append("***\\");
        sb.append(parts[parts.length - 2]).append("\\").append(parts[parts.length - 1]);
        return sb.toString();
    }
    public static String sanitizeToken(String token) { return "***"; }
    public static String sanitizeUsername(String text) {
        return text.replace(System.getProperty("user.name"), "<user>");
    }
}
```

### 6.5 诊断包

```java
public class DiagnosticPackager {
    public Path exportDiagnosticPackage() {
        Path zip = Files.createTempFile("terra-scout-diag-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            addFile(zos, "logs/terrascout.log", sanitizeLog());
            addFile(zos, "config/application.yml", readConfig());
            addFile(zos, "db/backup.mv.db", backupDb());
            addFile(zos, "system-info.txt", collectSystemInfo());
        }
        return zip;
    }
}
```

---

## 7. UI 设计规范（单色编辑部设计系统）

> 本节以 `tokens.css` + `theme.ts` 为实现权威，取代历史 UI 规范中的一切颜色/动效条款。

### 7.1 配色系统（强制）

- **全站仅允许 白 / 锌灰 / 黑**；最深深灰 `#18181b`（`gray-900`），**禁止纯黑 `#000` 大面积使用**。
- **全文禁蓝色**：主色不再是蓝色，主色 = `#27272a`（`gray-800`）；杜绝 antd 默认蓝（链接、选中、聚焦环一律灰化）。
- 完整锌灰阶（Light）：

| 令牌 | 色值 | 令牌 | 色值 |
|---|---|---|---|
| `gray-50` | `#fafafa` | `gray-500` | `#71717a` |
| `gray-100` | `#f4f4f5` | `gray-600` | `#52525b` |
| `gray-200` | `#e8e8ea` | `gray-700` | `#3f3f46` |
| `gray-300` | `#d4d4d8` | `gray-800` | `#27272a` |
| `gray-400` | `#a1a1aa` | `gray-900` | `#18181b` |

- 语义色收敛为低饱和档，仅出现在状态点 / 危险按钮 / 日志级别：`--color-success: #3f6212`、`--color-warning: #b45309`、`--color-error: #991b1b`。
- 日志深色面板固定色 `--ink-*`：面板底 `#18181b`、面板内发丝线 `#27272a`、正文 `#e8e8ea`、次信息 `#a1a1aa`（INFO）、`#d4a26a`（WARN）、`#d98c8c`（ERROR）；**日志面板是全页唯一深色视觉锚点**，任何主题下都保持深底浅字、不随 `prefers-color-scheme` 反转。

### 7.2 边框与按钮

- **1px hairline 发丝线**：卡片 / 输入框 / 标准按钮统一 `1px solid`（`gray-200` 或 `gray-300`）。
- **按钮规范（强制）**：
  - 禁止纯黑按钮；禁止蓝色主按钮。
  - 主/次按钮以「**浅灰填充 vs 白底描边**」区分：
    - **主按钮（antd primary，浅灰填充）**：默认 `gray-100`（`#f4f4f5`）底 + `gray-200`（`#e8e8ea`）边框 + `gray-900`（`#18181b`）字；hover 底加深为 `gray-200`、边框加深为 `gray-400`（`#a1a1aa`），字恒为 `gray-900`。
    - **标准/次级按钮（白底描边）**：`gray-50`（`#fafafa`）底 + `gray-300`（`#d4d4d8`）边框 + `gray-700`（`#3f3f46`）字；hover 边框加深 `gray-400`、字加深 `gray-900`。
  - 危险按钮：语义红仅作文字点缀，hover 才反白；禁用态 45% 透明 + `not-allowed`；按钮文字 ≤ 6 个汉字、一屏只一个主按钮、圆角 `8px`。
  - **Antd 按钮**：用 `--gray-200` / `--gray-900` 等灰阶，并靠 `ConfigProvider`（`theme.ts`）覆盖默认 token，去掉 antd 默认投影（`primaryShadow/dangerShadow/defaultShadow: none`）。

### 7.3 动效（强制）

- 时长 ≤ `300ms`（`--duration-slow: 300ms` 上限）；微交互 `--duration-snap: 180ms`。
- 复合曲线 `cubic-bezier(0.22, 1, 0.36, 1)`（`--ease-emphasized` / `--ease-out`，出场/淡出的减速收尾）。
- **hover 只加深边框并位移 1px**（卡片 `border-color` gray-200 → gray-400 + `transform: translateY(-1px)`，极轻阴影 `0 6px 20px rgba(24,24,27,0.06)`）。
- 只动画 `transform` 与 `opacity`；进度条用 `transform: scaleX`（禁 `width`）；骨架屏只动画 `opacity`；尊重 `prefers-reduced-motion`。

### 7.4 滚动条与留白

- **8px 自定义灰色滚动条**：`width/height: 8px`、透明轨道、滑块 `gray-300`、hover `gray-400`、圆角胶囊；Firefox `scrollbar-width: thin` 兜底。
- **页面留白节奏**：页头 `padding: 24px 0 12px`，正文区 `padding-top: 28px`（非对称留白）。

### 7.5 内核状态胶囊（KernelStatus）

- 右下角浮动胶囊，高 `34px`、全圆角、轻阴影，脱离文档流。
- 三态：**启动中**原灰点常驻 / **就绪**深灰点、4 秒后以复合曲线淡出（`opacity/transform 280ms cubic-bezier(0.22,1,0.36,1)`，不销毁 DOM）/ **崩溃**暖色点（`--color-warning #b45309`）+ 重启按钮（仅 GIVEUP 需要手动重启时出现）。

### 7.6 布局与页头（强制）

- 布局壳：左侧 `220px` 导航 + 右侧灰画布内容区；底栏 `32px`；内容最大宽度 `1280px`（编辑部式克制栏宽）。
- **语境化页头替代 PageTitle**：页头不放导航词大标题，只放该页上下文——情境化统计文本（`13px` gray-500）+ 操作按钮；禁止导航页重复文案。
- **四语言切换胶囊导航保留**：SDK 页 `Segmented`（JAVA / NODE / PYTHON / GO）滑动胶囊，数据加载不卸载、胶囊动画不被打断。
- **状态胶囊**：灰阶底 + 7px 语义色小点（完成绿 / 失败红 / 运行深灰 / 其余浅灰），语义色绝不染整块底色。
- **无 Java / antd 原生默认样式与顶部色条**：消灭 antd Menu 选中项的彩色指示条（`activeBarBorderWidth/activeBarHeight: 0`）、蓝色链接、蓝色聚焦环。

### 7.7 禁止（反模式）

- 禁 AI 套路效果：爱心粒子、常规螺旋、过度对称、模板参数组合、常见缓动、过度发光模糊、常见交互模式。
- 禁：蓝色主色与蓝链接、纯黑背景 + 纯白文本、渐变文字、多层阴影叠加、无意义发光、装饰性粒子背景、全屏模糊、自动播放、弹跳/回弹、旋转超 360°、视差滚动。

---

## 8. 页面设计约定

### 8.1 页面清单（7 页）

| 页面 | 路由 | 功能 |
|---|---|---|
| 首页 | `/` | 项目列表 + 导入 |
| 项目详情 | `/project/:id` | 项目画像 + 装配计划 |
| 任务中心 | `/tasks` | 任务列表 + 进度 |
| 任务详情 | `/task/:id` | 步骤 + 日志 |
| SDK 管理 | `/sdks` | 已装 SDK + 版本列表 |
| 设置 | `/settings` | AI 开关 + 镜像源 + 日志 |
| 关于 | `/about` | 版本 + 诊断包导出 |

### 8.2 交互约定（强制）

- 所有系统修改操作前二次确认；所有长任务显示进度条；所有错误显示「错误码 + 用户提示 + 操作按钮」；所有列表支持分页；所有操作支持撤销（如删除项目）。
- 首页导入流程：选择目录 → `POST /project/analyze` → 成功跳转项目详情，失败按错误码映射提示。装配：二次确认 → `POST /project/execute` → 跳转任务详情。
- 任务中心 / 详情运行中任务每 2 秒轮询 `GET /task/{taskId}` 更新进度（`refetchInterval: 2000`，无 active 任务停机，见 §4）。

### 8.3 错误提示映射（用户可读）

| 错误码 | UI 显示 | 颜色 | 按钮 |
|---|---|---|---|
| 400001 | 项目路径为空或格式不正确 | 红 | 重新选择 |
| 401001 | 认证失败，请重启工具 | 红 | 重启 |
| 404001 | 项目路径不存在 | 红 | 重新选择 |
| 409001 | 该项目已有任务在执行 | 黄 | 查看任务 |
| 422001 | 无法识别项目类型 | 红 | 查看帮助 |
| 422003 | 父 POM 未找到 | 红 | 查看详情 |
| 422007 | 版本已停止维护 | 黄 | 查看建议 |
| 422009 | 文件校验失败 | 红 | 重试 |
| 422010 | 解压检测到安全风险 | 红 | 查看详情 |
| 422011 | 路径过长 | 红 | 查看详情 |
| 500003 | 任务异常中断 | 红 | 重试 |
| 500004 | 回滚失败 | 红 | 手动清理 |
| 507001 | 磁盘空间不足 | 红 | 清理 |
| 502001 | SDK 源不可达：官方源与所有镜像均连接失败 | 红 | 重试 |

### 8.4 加载与空状态

| 场景 | 显示 |
|---|---|
| 页面加载中 | 骨架屏 |
| 项目列表为空 | 图标 + “还没有项目，点击导入” |
| 任务列表为空 | 图标 + “还没有任务” |
| SDK 列表为空 | 图标 + “还没有安装 SDK” |
| 网络错误 | 红色提示 + 重试按钮 |

### 8.5 SDK 版本徽标与安装进度

- **LTS 徽标**：`gray-200`（`#e8e8ea`）底 + `gray-700`（`#3f3f46`）字，灰阶小胶囊；「停止维护」暖色点缀（`gray-100` 底 + 暖色字）；「系统已装」中性灰。
- **SDK 安装进度阶段**：`QUEUED → DOWNLOADING → EXTRACTING → DONE`（完整链 `QUEUED → STARTING → DOWNLOADING → EXTRACTING → FINISHING → DONE`，取消链路 `CANCELLING → CANCELLED`）。
- **安装路径透明（红线）**：落位路径任务注册即确定、全程可见——列表行、进度条下方、安装确认弹层均实时展示 `installPath`/`installTargetPath`。
- **取消即清理（红线）**：下载块 / 阶段边界中止，即时清理 `.part` 与半解压目录后收敛为 `CANCELLED`；排队期取消直接收敛、不产生任何文件。

---

## 9. Git 规范

### 9.1 提交规范

```text
<type>(<scope>): <subject>
feat(parser): 支持父子 POM 递归解析
fix(task): 修复心跳超时未更新状态
docs(api): 补充 REST 接口 OpenAPI 定义
test(download): 增加断点续传测试
refactor(env): 重构环境注入逻辑
```

### 9.2 分支策略

```text
main        生产分支
develop     开发分支
feature/*   功能分支
hotfix/*    紧急修复
release/*   发布准备
```

---

## 10. 质量门禁

本节仅做引用，不重复 testing 文档内容。

- **前端类型检查 / 构建**：`npx tsc --noEmit`（严格模式）与 `npm run build` 必须通过；ESLint + Stylelint 强制「禁硬编码颜色/间距」（`color-no-hex` 等规则）；bundlesize 预算——首屏 JS < 200KB（gzip）、单路由 JS < 300KB（gzip）、Monaco chunk < 700KB（gzip，懒加载）、单页 CSS < 50KB；无障碍扫描（axe）与视觉回归（playwright）纳入 CI。
- **后端**：编译、单元/集成测试与覆盖率要求见 testing 相关文档；`mvn verify`（或等价门禁）须通过。
- 详细测试策略、用例与数据见《testing》标准文档，此处不展开。