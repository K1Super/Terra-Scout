# Terra Scout 总体设计

> 文档版本 V3.0 | 更新日期 2026-09-18 | 适用范围 P0 单机桌面版
> 本文档为跨专项文档的总体收敛视图，覆盖架构、ADR、任务引擎、并发、异常、进程、安全、核心算法与目录结构。任何矛盾处均按 SRS.md「最高裁决权」原则处理并留痕标注，冲突方以权威链高优先级文档为准。

## 1. 架构概述

### 1.1 总体双进程架构

```text
┌───────────────────────────────┐
│ Electron UI 进程（仅 GUI）     │
│ React + AntD + Monaco         │
└───────────────┬───────────────┘
                │ HTTP 127.0.0.1 + X-TerraScout-Token 拦截器鉴权
                ▼
┌──────────────────────────────────────────────┐
│ SpringBoot Java 单机内核（单 JVM）           │
│  Controller + Interceptor                    │
│  ├── terra-scout-parser   项目解析              │
│  ├── terra-scout-download SDK 下载/校验/解压    │
│  ├── terra-scout-task     任务引擎/状态机/心跳  │
│  ├── terra-scout-env      环境注入/进程执行     │
│  └── H2 文件数据库（内嵌）                   │
└──────────────────────────────────────────────┘
```

双进程：Electron UI 进程（仅 GUI，React + AntD + Monaco）通过 HTTP `127.0.0.1` + `X-TerraScout-Token` 拦截器鉴权，调用 SpringBoot Java 单机内核（单 JVM，内嵌 H2 文件数据库，零外部中间件）。

### 1.2 模块依赖图

```text
terra-scout-app
  ├── terra-scout-core
  ├── terra-scout-parser
  ├── terra-scout-download
  ├── terra-scout-task
  └── terra-scout-env
terra-scout-parser → terra-scout-core
terra-scout-download → terra-scout-core
terra-scout-task → terra-scout-core
terra-scout-env → terra-scout-core
```

无循环依赖。

### 1.3 数据流图

```text
项目路径
  → ProjectTypeDetector → 项目类型
  → PomParser → 版本约束
  → VersionMatcher → 装配计划
  → SdkInstaller → SDK 安装路径
  → ProjectEnvIsolator → .devenv
  → DependencyInstaller → 依赖清单
  → EnvInjector → 环境变量 Map
  → ProcessExecutor → 验证结果
  → AuditLogger → 审计记录
```

### 1.4 时序图（项目装配）

```text
Electron → Controller: POST /project/analyze
Controller → ProjectService: analyze(path)
ProjectService → ProjectTypeDetector: detect(path)
ProjectService → PomParser: parse(path)
ProjectService → VersionMatcher: match(constraints)
ProjectService → Controller: AnalyzeResponse
Controller → Electron: 200 OK
Electron → Controller: POST /project/execute
Controller → TaskService: createTask(planId)
TaskService → TaskRepository: save(task)
TaskService → ThreadPoolExecutor: submit(task)
TaskService → Controller: ExecuteResponse
Controller → Electron: 200 OK（异步语义由 taskId + 轮询表达，D-002）
ThreadPoolExecutor → TaskEngine: run(task)
TaskEngine → SdkInstaller: install()
TaskEngine → ProjectEnvIsolator: create()
TaskEngine → DependencyInstaller: install()
TaskEngine → EnvInjector: inject()
TaskEngine → ProcessExecutor: verify()
TaskEngine → TaskRepository: updateStatus(SUCCESS)
```

## 2. 模块设计

7 大模块职责与依赖关系如下，包结构见 §10。

| 模块 | 职责 | 依赖 |
|---|---|---|
| terra-scout-core | 领域模型（Project / SdkVersion / SdkInstallRecord / Task / TaskStep / CommandExecution / AuditLog）、枚举、46 错误码、DTO、`PathConstants` 统一路径常量 | 无 |
| terra-scout-parser | 项目类型探测、`pom.xml` 解析、属性求值、约束提取 | → core |
| terra-scout-download | SDK 下载、断点续传（Range + ETag）、SHA-256 校验、Zip4j 解压四重检查 | → core |
| terra-scout-task | 任务引擎 `TaskEngine`、状态机 `TaskStateMachine`、步骤执行器接口、心跳检测 `HeartbeatMonitor`、按 projectId 分锁 `LockManager` | → core |
| terra-scout-env | 环境注入 `EnvInjector`、进程执行 `ProcessExecutor`、命令白名单 `CommandWhitelist`、`env.ps1` 生成 `EnvScriptGenerator` | → core |
| terra-scout-app | SpringBoot 启动、Controller / Interceptor / Service / Repository、全局异常处理器、TaskJournal / TaskAssemblyOrchestrator（异步编排）、Flyway 迁移 | → core/parser/download/task/env |
| terra-scout-electron | Electron + React UI（独立 npm 工程，不进 Maven reactor）：spawn Java / READY 握手 / 崩溃重启 / 优雅退出 | 经 HTTP 调 app |

模块开发顺序：core → parser → download → task → env → app → electron。

## 3. 架构决策记录

> Terra Scout 环境感知编排平台 · P0 关键架构决策。每条 ADR 记录：决策、理由、备选方案、后果。

### 3.1 ADR-001：为什么用 H2 不用 SQLite

| 项 | 内容 |
|---|---|
| 决策 | 使用 H2 文件数据库 |
| 理由 | Java 原生，无原生库依赖，jlink 打包简单，支持事务和 WAL |
| 备选 | SQLite（需 JDBC 驱动 + 原生库，跨平台打包复杂） |
| 后果 | 桌面工具用 H2 不常见，但 Java 生态合理；体积约 2.5MB |

### 3.2 ADR-002：为什么用 ProcessBuilder 不用 JNI

| 项 | 内容 |
|---|---|
| 决策 | 使用 `ProcessBuilder` 调用外部命令 |
| 理由 | 跨平台、无编译依赖、参数数组防注入、环境变量 Map 可控 |
| 备选 | JNI（复杂、平台相关、维护成本高） |
| 后果 | 依赖外部命令存在，需白名单 + 超时 + 审计 |

### 3.3 ADR-003：为什么用线程池不用 CompletableFuture

| 项 | 内容 |
|---|---|
| 决策 | 使用 `ThreadPoolExecutor` + `ReentrantLock` |
| 理由 | 任务需持久化、可暂停、可恢复、可回滚，`CompletableFuture` 不适合有状态任务 |
| 备选 | `CompletableFuture`（适合无状态异步，不适合长任务） |
| 后果 | 需自己管理线程池、队列、拒绝策略 |

### 3.4 ADR-004：为什么用 ReentrantLock 不用 synchronized

| 项 | 内容 |
|---|---|
| 决策 | 使用 `ReentrantLock` |
| 理由 | 支持超时、可中断、公平锁、按 key 分锁 |
| 备选 | `synchronized`（无法超时、无法按 key 分锁） |
| 后果 | 必须手动 `unlock`，需 try-finally 保证 |

### 3.5 ADR-005：为什么用 Electron 不用 JavaFX

| 项 | 内容 |
|---|---|
| 决策 | Electron + React 做 UI |
| 理由 | Web 生态成熟、Monaco Editor 现成、UI 开发快、跨平台 |
| 备选 | JavaFX（单进程、轻量，但 UI 生态弱） |
| 后果 | 双进程架构、安装包 200-300MB、内存 250-400MB |

### 3.6 ADR-006：为什么 P0 只做 Windows

| 项 | 内容 |
|---|---|
| 决策 | P0 仅 Windows 10/11 |
| 理由 | Windows 环境变量、路径、权限模型最复杂，先攻克；macOS/Linux 相对简单 |
| 备选 | 三平台同时做（工期翻倍） |
| 后果 | 枚举和字段预留，P1 扩展 |

### 3.7 ADR-007：为什么不做全局环境变量修改

| 项 | 内容 |
|---|---|
| 决策 | 只做进程级注入 |
| 理由 | 全局修改不可回滚、影响其他程序、需管理员权限、易冲突 |
| 备选 | 修改注册表（不可控） |
| 后果 | 用户需通过 `devenv shell` 或 `env.ps1` 使用项目环境 |

### 3.8 ADR-008：为什么 AI 只做诊断不做执行

| 项 | 内容 |
|---|---|
| 决策 | AI 只输出建议，不执行命令 |
| 理由 | LLM 幻觉不可控、命令执行风险高、用户信任 |
| 备选 | AI 自动执行（危险） |
| 后果 | AI 价值边界清晰，关闭后核心功能可用 |

## 4. 任务引擎设计

### 4.1 任务状态机

**任务状态（9 个）：**

```text
PENDING        已创建，未入队
QUEUED         已入队，等待执行
RUNNING        正在执行
PAUSED         已暂停，可恢复
SUCCESS        成功完成
FAILED         执行失败
CANCELLED      用户取消
ROLLING_BACK   正在回滚
ROLLED_BACK    回滚完成
```

**事件（11 个）：**

```text
ENQUEUE        入队
START          开始执行
PAUSE          暂停
RESUME         恢复
COMPLETE       完成
FAIL           失败
CANCEL         取消
RETRY          重试
ROLLBACK       回滚
ROLLBACK_DONE  回滚完成
ROLLBACK_FAIL  回滚失败
```

**转移表：**

| 当前 | 事件 | 条件 | 动作 | 下一状态 |
|---|---|---|---|---|
| PENDING | ENQUEUE | 队列未满 | 写 H2 + 入队 | QUEUED |
| PENDING | CANCEL | - | 清理 | CANCELLED |
| QUEUED | START | 获取锁成功 | 执行 | RUNNING |
| QUEUED | START | 获取锁失败 | 重新入队 | QUEUED |
| QUEUED | CANCEL | - | 出队 | CANCELLED |
| RUNNING | PAUSE | 当前步骤可暂停 | 保存断点 | PAUSED |
| RUNNING | COMPLETE | 所有步骤成功 | 写审计 | SUCCESS |
| RUNNING | FAIL | 步骤失败 | 记录错误 | FAILED |
| RUNNING | CANCEL | - | 中止 | CANCELLED |
| PAUSED | RESUME | 锁可用 | 从断点继续 | RUNNING |
| PAUSED | CANCEL | - | 清理 | CANCELLED |
| FAILED | RETRY | retry < max | 重置步骤 | QUEUED |
| FAILED | ROLLBACK | 有回滚点 | 执行补偿 | ROLLING_BACK |
| FAILED | CANCEL | - | 清理 | CANCELLED |
| ROLLING_BACK | ROLLBACK_DONE | 补偿成功 | 写审计 | ROLLED_BACK |
| ROLLING_BACK | ROLLBACK_FAIL | 补偿失败 | 告警 | FAILED |
| ROLLED_BACK | RETRY | - | 重新入队 | QUEUED |

锁机制：使用 `ReentrantLock`，按 `projectId` 分锁，每个项目一把锁，防止同一项目并发装配；锁超时：任务级别不设超时，但心跳检测（见 §5）。

### 4.2 任务类型

```java
public enum TaskTypeEnum {
    PROJECT_ASSEMBLE,     // 项目装配
    SDK_INSTALL,          // SDK 安装
    SDK_UNINSTALL,        // SDK 卸载
    DEPENDENCY_INSTALL,   // 依赖安装
    VERIFY_PROJECT        // 项目验证
}
```

### 4.3 PROJECT_ASSEMBLE 11 步装配编排（四语言，裁决 R48）

`QUEUED → RUNNING → SUCCESS / FAILED`：`TaskService.execute` 创建 QUEUED 并在事务提交后异步触发 `TaskAssemblyOrchestrator.run(taskId, projectId, versionOverrides)`；`TaskJournal.markRunning` 置 RUNNING；引擎推进 11 步（JAVA / NODE / GO / PYTHON 四语言端到端一致）；终态落 SUCCESS/FAILED。

| 序号 | 步骤名 | 输入 | 输出 | 超时 | 可暂停 | 可重试 | 回滚操作 |
|---|---|---|---|---|---|---|---|
| 0 | `DETECT_PROJECT` | 项目路径 | 项目类型 | 10s | 否 | 是 | 无 |
| 1 | `PARSE_MANIFEST` | 项目路径 | 版本约束列表 | 30s | 否 | 是 | 无 |
| 2 | `MATCH_VERSION` | 约束 + 可用版本 + versionOverrides | 装配计划 | 10s | 否 | 是 | 无 |
| 3 | `INSTALL_SDK_JAVA` | SDK 版本 | 安装路径 | 30min | 是 | 是 | 删除安装目录 |
| 4 | `INSTALL_SDK_NODE` | SDK 版本 | 安装路径 | 30min | 是 | 是 | 删除安装目录 |
| 5 | `INSTALL_SDK_GO` | SDK 版本 | 安装路径 | 30min | 是 | 是 | 删除安装目录 |
| 6 | `INSTALL_SDK_PYTHON` | SDK 版本 | 安装路径 | 30min | 是 | 是 | 删除安装目录 |
| 7 | `CREATE_ISOLATION` | 项目路径 | `.devenv` | 10s | 否 | 是 | 删除 `.devenv` |
| 8 | `INSTALL_DEPENDENCIES` | 隔离路径 | 依赖清单 | 30min | 是 | 是 | 清空 `.devenv/m2`（Go 缓存 `.devenv/go-cache`） |
| 9 | `BIND_ENV` | 安装路径 | `env.ps1` | 10s | 否 | 是 | 删除 `env.ps1` |
| 10 | `VERIFY_PROJECT` | 项目路径 | 验证结果 | 10min | 否 | 是 | 无 |

> SDK 版本选配（裁决 R48）：预览 `/project/preview-plan` 返回的每个 `SdkInstallItem.candidates` 供用户选择（首项 = 自动推荐）；`POST /task/execute` 载荷携带 `versionOverrides`（`{language, version}`）覆盖，`MATCH_VERSION` 步通过 `SdkVersionMatcher.overrideVersion` 做同源校验（只许落在候选集内，否则 422006）。安装步骤按语言分步（Java / Node 已有，Go / Python 为 R48 新增），互不影响换装。

> 裸命令名 PATH 钉死（裁决 R48）：Windows 上 JDK 以裸名（如 `go.exe`）启动子进程时，CreateProcess 按父进程（JVM）PATH 定位可执行文件，注入 env 的 PATH 不参与解析，会错配到系统安装的同名工具（实测：系统 32 位 go.exe 搭配项目 64 位 GOROOT 秒失败）。`ProcessExecutor.execute`（装配面）在 spawn 前把白名单映射出的裸名按注入 PATH 逐目录解析为绝对路径；未命中保持裸名回退既有语义，探测面不受影响。

> SDK 安装步骤超时 30 分钟（D-018）：300MB 级安装包在慢链路下 10 分钟不足，验收指标「SDK 安装成功率 ≥ 98%」要求余量。

重试（`MAX_RETRY=3`）：默认为 3；重试从失败的步骤开始，不从头开始；每次重试 `retry_count` + 1；超过 `max_retry` 进入 FAILED，提示用户手动回滚。

回滚：每个步骤定义 `rollback_json`；按步骤逆序执行；回滚失败记录 `500004`（回滚执行失败），任务保持 `ROLLING_BACK` 或 `FAILED`；回滚成功步骤标记 `ROLLED_BACK`。步骤回滚示例：

| 步骤 | 前向操作 | 回滚操作 |
|---|---|---|
| INSTALL_SDK | 下载解压到 SDK 仓库 `~\.terrascout\sdks\{lang}\{ver}`（D-004） | 删除对应安装目录 |
| ISOLATE_PROJECT | 创建 `.devenv/m2` | 删除 `.devenv/m2` |
| INSTALL_DEPS | 执行 `mvn install` | 删除 `.devenv/m2` 内容 |
| BIND_ENV | 生成 `env.ps1` | 删除 `env.ps1` |
| VERIFY | 执行 `mvn compile` | 无需回滚 |

幂等：`idempotency_key` 唯一约束；相同 key 返回已有任务；步骤执行前检查 `task_step.status`。

### 4.4 步骤状态机与执行器

步骤状态枚举（与任务状态独立）：

```java
public enum StepStatusEnum {
    PENDING,      // 待执行
    RUNNING,      // 执行中
    SUCCESS,      // 成功
    FAILED,       // 失败
    SKIPPED,      // 跳过
    ROLLED_BACK   // 已回滚
}
```

步骤执行器统一接口：

```java
public interface TaskStepExecutor {
    StepStatusEnum execute(TaskStepContext context);
    void rollback(TaskStepContext context);
    boolean isPausable();
    boolean isRetryable();
    int getTimeoutSeconds();
}
```

```java
public class TaskStepContext {
    private String taskId;
    private String projectId;
    private Path projectRoot;
    private Path isolationDir;
    private Map<String, Object> input;
    private Map<String, Object> output;
    private Map<String, Object> rollbackData;
}
```

### 4.5 崩溃恢复流程

```text
程序启动
  → 扫描 H2 中 status = RUNNING 的任务
  → 检查 heartbeat_at 是否超时（> 60s）
  → 超时任务标记 FAILED，errorCode = 500003
  → 未超时任务保留（可能是其他线程）
  → UI 展示失败任务，提供重试/回滚入口
  → 下载任务保留分片，支持续传
```

### 4.6 app 层异步装配编排（TaskAssemblyOrchestrator + TaskJournal）

任务真实落库与异步推进由 app 层完成（`TaskEngine` 仅同步阻塞、不落库）：

- `TaskAssemblyOrchestrator`（`terra-scout-app/service/TaskAssemblyOrchestrator.java`）：异步编排器。`run(taskId, projectId)`：`planId`（即 `projectId`）→ `Project` 解析（不存在 → `404003 PLAN_NOT_FOUND`）；`MAX_RETRY = 3`；`buildResolver` 按 9 步名装配对应执行器（DetectProjectStep / ParseManifestStep / MatchVersionStep / InstallSdkStep(Java) / InstallSdkStep(Node) / CreateIsolationStep / InstallDependenciesStep / BindEnvStep / VerifyProjectStep）；`try (TaskEngine)` 内调用 `engine.execute(projectId, TaskStepDefinition.projectAssemble(), resolver, () -> journal.heartbeat(taskId), MAX_RETRY)`，结束后 `complete` 落终态；异常整体 try-catch 写 FAILED 终态（错误码传递：executor 失败写 `session.errorCode/errorMsg`，orchestrator 结束读 session 回写 `task.errorCode`；TaskEngine 正常失败时 `result.errorCode` 为 null，仅回滚失败设 500004，故终态码取 `result.errorCode() != null ? result.errorCode() : session.getErrorCode()`）。
- `TaskJournal`（`terra-scout-app/service/TaskJournal.java`）：任务/步骤/命令执行三类实体落库。方法：`markRunning` / `heartbeat` / `stepStarted` / `stepFinished` / `stepFailed` / `stepRolledBack` / `complete` / `recordCommand`；**每方法独立 `@Transactional`，编排线程不持长事务**。
- `afterCommit` 异步触发：`TaskService.execute` 流程为「confirm 校验 → planId(projectId) 解析 → 幂等去重 → 创建 QUEUED → `afterCommit` 提交 orchestrator.run」。`afterCommit(callback)` 经 `TransactionSynchronizationManager.registerSynchronization` 在 `afterCommit` 中 `taskExecutor.submit(callback)`。

> 口径标注（经源码核对）：§5.1 任务执行线程池与 app 层实际承载 afterCommit 异步编排的是 `BeansConfig` 注册的 `ThreadPoolTaskExecutor`（`corePoolSize=2` / `maxPoolSize=4` / `queueCapacity=100`，bean 名 `taskExecutor`）。两者同名 `taskExecutor` 但类型与核心线程数口径不一致，本总体视图保留双方原述并以 §5.1 线程池配置为主口径，app 层 `ThreadPoolTaskExecutor(2/4/100)` 作为异步编排实现口径并列列出。

## 5. 并发模型

> V2.1 修复：心跳改为任务线程自报（D-007，原全局盲目刷新会掩盖卡死任务）；队列满错误码修正为 409006（D-001）；移除 Guava 依赖，ThreadFactory 自研（D-012）。

### 5.1 线程池配置

不引入 Guava（依赖最小化，SRS.md D-012），ThreadFactory 自研：

```java
public final class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger seq = new AtomicInteger(0);

    public NamedThreadFactory(String prefix) { this.prefix = prefix; }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
        t.setDaemon(false);
        return t;
    }
}
```

```java
@Configuration
@EnableScheduling
public class ThreadPoolConfig {

    @Bean("taskExecutor")
    public ThreadPoolExecutor taskExecutor() {
        return new ThreadPoolExecutor(
            4,                                            // 核心线程数
            4,                                            // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),               // 队列容量
            new NamedThreadFactory("terra-scout-task"),
            new ThreadPoolExecutor.AbortPolicy()          // 拒绝策略：抛 RejectedExecutionException
        );
    }

    @Bean("heartbeatExecutor")
    public ScheduledExecutorService heartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("terra-scout-heartbeat"));
    }

    @Bean("cleanupExecutor")
    public ScheduledExecutorService cleanupExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("terra-scout-cleanup"));
    }
}
```

### 5.2 锁策略

```java
@Component
public class LockManager {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock getLock(String projectId) {
        return locks.computeIfAbsent(projectId, k -> new ReentrantLock(true));
    }

    public boolean tryLock(String projectId, long timeoutMs) throws InterruptedException {
        return getLock(projectId).tryLock(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void unlock(String projectId) {
        ReentrantLock lock = locks.get(projectId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

> locks map 随项目数线性增长，桌面规模（<1000 项目）可接受，不做过期清理（YAGNI，见 SRS.md §6）。

### 5.3 并发规则

| 场景 | 策略 |
|---|---|
| 同一项目多任务 | 串行，`ReentrantLock` 按 `projectId` 分锁 |
| 不同项目任务 | 并行，线程池 4 线程 |
| 队列满 | `AbortPolicy` 抛 `RejectedExecutionException` → 捕获后返回 **409006**（任务队列已满） |
| 锁等待超时 | 返回 409001（任务正在执行） |
| 心跳更新 | 任务线程自报（见 5.4） |
| 超时检测 | 单线程调度，每 30s 扫描，只检测不更新 |
| 清理线程 | 单线程，每小时执行（备份清理 / 审计保留期） |
| H2 写并发 | 单写多读，通过 `synchronized` 或队列串行化写 |

### 5.4 心跳机制（D-007：谁执行，谁心跳）

**设计原则：任务线程自报心跳，调度线程只检测、不刷新。**

> V2.0 缺陷说明：若由全局调度线程统一刷新所有 RUNNING 任务的心跳，则活 JVM 内卡死/死锁的任务永远不会被判超时，心跳只能检测整机崩溃，形同虚设。已修复。

```java
// TaskEngine：任务开始时注册自身心跳，结束时注销
public void run(Task task) {
    ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
        () -> taskRepository.updateHeartbeatAt(task.getId(), System.currentTimeMillis()),
        0, 5000, TimeUnit.MILLISECONDS);
    try {
        executeSteps(task);
    } finally {
        heartbeat.cancel(false);
        taskRepository.updateStatus(task.getId(), /* 终态 */);
    }
}
```

```java
// HeartbeatMonitor：只检测，不更新心跳
@Component
public class HeartbeatMonitor {

    @Scheduled(fixedRate = 30000)
    public void detectTimeout() {
        long deadline = System.currentTimeMillis() - 60000;
        taskRepository.findByStatusAndHeartbeatAtBefore(RUNNING, deadline)
            .forEach(task -> {
                task.setStatus(FAILED);
                task.setErrorCode(500003);
                task.setErrorMsg("任务心跳超时");
                taskRepository.save(task);
            });
    }
}
```

时间参数来源 `terrascout.task.*`：更新间隔 5s / 超时阈值 60s / 检测周期 30s。

### 5.5 启动恢复扫描

`ApplicationReadyEvent` 后执行一次：

```text
扫描 status = RUNNING 且 heartbeat_at < now - 60s → 标记 FAILED，errorCode = 500003
下载任务保留 .part 分片 → 支持续传
```

## 6. 异常处理

**错误码族映射规则（D-001 / SRS.md §6）**：`HTTP 状态码 == code ÷ 1000`（6 位数字 = 前 3 位 HTTP + 后 3 位业务序号）。鉴权失败 = HTTP `401` + 业务码 `401001`（HTTP 前缀与业务码前缀强一致，禁止「401 载荷配 403 码」错位）。

### 6.1 异常分类

| 类型 | 处理 | 重试 | 回滚 |
|---|---|---|---|
| 参数错误（400/404） | 直接返回 | 否 | 否 |
| 鉴权错误（401） | 直接返回 | 否 | 否 |
| 权限错误（403） | 直接返回 | 否 | 否 |
| 状态冲突（409） | 直接返回 | 否 | 否 |
| 业务校验（422） | 返回 + 记录 | 部分 | 部分 |
| 内部错误（500） | 返回 + 告警 | 部分 | 部分 |
| 第三方错误（502） | 返回 + 重试 | 是 | 否 |
| 容量不足（507） | 返回 + 提示 | 否 | 否 |

### 6.2 异常传递

```java
// Service 层
public AnalyzeResponse analyze(AnalyzeRequest request) {
    try {
        return doAnalyze(request);
    } catch (TerraScoutException e) {
        throw e;
    } catch (Exception e) {
        throw new TerraScoutException(TerraScoutError.UNKNOWN, e.getMessage(), e);
    }
}
```

### 6.3 全局异常处理器

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(TerraScoutException.class)
    public ResponseEntity<ApiResponse<Void>> handle(TerraScoutException e) {
        TerraScoutError err = e.getError();
        log.warn("[{}] {}: {}", MDC.get("traceId"), err.getCode(), err.getMessage());
        return ResponseEntity.status(err.getHttpStatus())
            .body(ApiResponse.fail(err, e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(400)
            .body(ApiResponse.fail(TerraScoutError.FIELD_MISSING, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("[{}] Unknown error", MDC.get("traceId"), e);
        return ResponseEntity.status(500)
            .body(ApiResponse.fail(TerraScoutError.UNKNOWN, e.getMessage()));
    }
}
```

### 6.4 任务级异常处理

```java
public class TaskStepExecutor {
    public void execute(Task task, TaskStep step) {
        try {
            StepStatusEnum status = stepExecutor.execute(context);
            step.setStatus(status);
            step.setFinishedAt(System.currentTimeMillis());
            stepRepository.save(step);
        } catch (TerraScoutException e) {
            step.setStatus(FAILED);
            step.setErrorCode(e.getError().getCode());
            step.setErrorMsg(e.getMessage());
            stepRepository.save(step);
            throw e;
        }
    }
}
```

### 6.5 错误码到用户提示映射

错误码是给开发看的，用户看到的是文案。

| 错误码 | 用户提示 |
|---|---|
| 400001 | 项目路径为空或格式不正确，请重新选择。 |
| 401001 | 认证失败，请重启工具。 |
| 403002 | 命令包含不允许的字符，已拒绝执行。 |
| 409006 | 任务队列已满，请稍后重试。 |
| 404001 | 项目路径不存在，请检查路径是否正确。 |
| 409001 | 该项目已有任务在执行，请等待完成。 |
| 409003 | 隔离域被占用，请关闭相关程序后重试。 |
| 422001 | 无法识别项目类型，请确认项目包含 pom.xml / package.json / go.mod / .python-version / pyproject.toml。 |
| 422003 | 项目引用了父 POM，但未找到。请确认父 POM 已发布。 |
| 422006 | 项目要求的版本不存在，请检查版本约束。 |
| 422007 | 项目要求的版本已停止维护，建议升级。 |
| 422009 | 文件校验失败，可能下载损坏，请重试。 |
| 422010 | 解压时检测到安全风险，已中止。 |
| 422011 | 项目路径过长，请移动到更短的路径。 |
| 500003 | 任务异常中断，请重试。 |
| 500004 | 回滚失败，请手动清理 `.devenv` 目录。 |
| 507001 | 磁盘空间不足，请清理后重试。 |
| 502001 | 下载源不可达，请检查网络。 |
| 502002 | 镜像校验不一致，已中止。请更换镜像。 |

## 7. 进程通信与管理

### 7.1 双进程模型

```text
Electron 主进程（Node.js）
  ├── 渲染进程（Chromium）
  ├── GPU 进程
  ├── Utility 进程
  └── Java 子进程（spawn）
```

### 7.2 启动流程与握手

```text
1. Electron 启动
2. Java 绑定 0，由系统分配空闲端口
3. 生成随机 Token（32 字节 Base64，见 §8.1）
4. spawn java -jar terrascout.jar --port=<port> --token=<token>
5. 监听 Java stdout，等待 "READY <actualPort>"
6. 超时 10s 未就绪，提示启动失败
7. 记录 PID 和端口到内存
8. UI 加载完成，通过 HTTP 调用 Java
```

> 口径标注：早期草案曾表述「扫描空闲端口（或使用 0 让系统分配）」，但定稿口径与源码 `java-process.ts` 均为 `--server.port=0` 由系统分配、实际端口经 stdout `READY <actualPort>` 回传、不写配置文件不落盘。本视图统一采用「port=0 系统分配」口径。

### 7.3 Java 启动参数

```bash
java -jar terrascout.jar \
  --server.address=127.0.0.1 \
  --server.port=0 \
  --terrascout.token=<base64> \
  --terrascout.data-dir=%USERPROFILE%\.terrascout
```

- `--server.port=0`：系统分配空闲端口。
- Java 启动后打印 `READY <actualPort>` 到 stdout。
- Electron 解析 stdout 获取实际端口。
- `-Djava.net.preferIPv4Stack=true`：`java-process.ts` `buildArgs()` 追加该 JVM 参数（强制 IPv4 栈）。Windows 默认 IPv6 优先，部分官方源（python.org→Fastly 等）的 AAAA 路由不可达会导致抓取连接超时；IPv4 实测稳定。

### 7.4 崩溃检测

```ts
javaProcess.on('exit', (code, signal) => {
  if (!isShuttingDown) {
    logger.error(`Java exited: code=${code}, signal=${signal}`);
    notifyUI('Java 内核异常退出，请重启');
    autoRestart();
  }
});
```

- 自动重启最多 3 次。
- 每次间隔 2s。
- 超过 3 次，提示用户手动重启。

### 7.5 优雅退出

```text
1. Electron 收到退出信号
2. 设置 isShuttingDown = true
3. HTTP POST /api/v1/shutdown
4. Java 收到后：
   - 停止接收新任务
   - 等待当前任务完成（最多 5s）
   - 释放所有锁
   - 写入审计日志
   - 关闭 H2 连接
   - 返回 200
5. Electron 等待 Java 进程退出（最多 5s）
6. 超时则强制 kill
7. Electron 退出
```

### 7.6 端口冲突

- Java 绑定 `0`，由系统分配。
- 实际端口通过 stdout 传给 Electron。
- 不写配置文件，不落盘。

### 7.7 更新流程（P1）

```text
1. 下载新版本到临时目录
2. 校验签名
3. 提示用户重启
4. Electron 退出，Java 退出
5. 安装器替换文件
6. 下次启动生效
```

P0 不做自动更新，手动下载安装。

### 7.8 日志与诊断包

- Java 日志：`%USERPROFILE%\.terrascout\logs\terrascout.log`
- Electron 日志：`%USERPROFILE%\.terrascout\logs\electron.log`
- 滚动：按天 + 按大小（10MB）
- 保留：7 天
- 诊断包：一键导出「日志 + 配置 + 环境信息 + H2 备份」，脱敏（路径、token、用户名），输出 ZIP 文件。

## 8. 安全设计

> V2.1 修复：Token 失败统一 HTTP 401 + 业务码 401001（D-001）；参数黑名单升级为白名单正则，覆盖 cmd.exe 全部元字符（D-008）；数据库密码持久化（D-006）；新增威胁模型（D-016）。

### 8.1 Token 生成与传递

```java
byte[] bytes = new byte[32];
new SecureRandom().nextBytes(bytes);
String token = Base64.getEncoder().encodeToString(bytes);
```

- Electron 启动时生成，每次进程生命周期内不变
- 通过命令行参数传给 Java（已知暴露风险，见 8.11）
- Electron 内存保存，不落盘
- 进程退出即失效

> 口径标注：实际落地由 Electron 主进程 `kernel-info.ts` 以 Node `crypto.randomBytes(32).toString('base64')` 生成（仅内存，D-016）；上述 `SecureRandom` + Base64 片段为「32 字节随机 Token」的算法示意。生成侧以 Electron `crypto.randomBytes(32)` 为准，Token 统一经 `--terrascout.token=` 传 Java。

### 8.2 Token 校验

```java
@Component
public class TokenInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler)
            throws IOException {
        String token = req.getHeader("X-TerraScout-Token");
        if (!constantTimeEquals(token, expectedToken)) {
            resp.setStatus(401);   // HTTP 状态码与业务码 401001 前缀强一致（D-001）
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"code\":401001,\"message\":\"Token 缺失或校验失败\","
                + "\"traceId\":\"" + MDC.get("traceId")
                + "\",\"timestamp\":" + System.currentTimeMillis() + "}");
            return false;
        }
        return true;
    }
}
```

- 常量时间比较，防时序攻击
- 免鉴权白名单：仅 `GET /api/v1/health`
- 所有 Controller 在注册时校验路径是否在白名单，防止新端点漏拦截

### 8.3 CSP 与 Electron 安全

```ts
new BrowserWindow({
  webPreferences: {
    contextIsolation: true,
    nodeIntegration: false,
    sandbox: true,
    webSecurity: true,
    allowRunningInsecureContent: false,
    preload: path.join(__dirname, 'preload.js')
  }
});
```

```text
default-src 'self';
script-src 'self';
style-src 'self' 'unsafe-inline';
img-src 'self' data:;
connect-src 'self' http://127.0.0.1:*;
object-src 'none';
base-uri 'none';
frame-ancestors 'none';
```

### 8.4 命令执行

```java
List<String> cmd = new ArrayList<>();
cmd.add("cmd.exe");
cmd.add("/c");
cmd.add("mvn.cmd");
cmd.addAll(args);          // 每个 arg 必须通过白名单正则校验
ProcessBuilder pb = new ProcessBuilder(cmd);
pb.directory(new File(projectRoot));
pb.environment().putAll(envMap);
pb.redirectErrorStream(false);
Process p = pb.start();
```

- 禁止字符串拼接
- `.cmd` 需通过 `cmd.exe /c` 调用
- **参数白名单正则**（默认，D-008）：`^[A-Za-z0-9@+=:,._/\\-]+$`
  - 覆盖 cmd.exe 全部元字符：`&` `|` `;` `>` `<` \` ` `$` `%` `^` `(` `)` `!` `"` `'`
  - V2.0 黑名单（仅 7 字符）漏掉 `%` `^` `(` `)` `!`，cmd.exe 下仍可环境变量展开，已废弃黑名单方案
- npm 默认加 `--ignore-scripts`
- 超时：10 分钟（`terrascout.command.timeout-ms`），超时 kill
- 每次执行写 `command_execution` 审计记录

### 8.5 命令白名单（双面，R30）

```yaml
# 装配面（D-008 原状）：任务执行工作面，限 mvn/npm/java/node
command:
  whitelist:
    - mvn.cmd
    - npm.cmd
    - java.exe
    - node.exe

# 探测面（R30）：仅 SystemSdkProber 硬编码只读指令使用（java/node/py/python/go），
# 与装配面严格分离，不放大任务执行命令面
probe:
  whitelist:
    - java.exe
    - node.exe
    - py.exe      # Python Launcher（py -0p 列全部解释器）
    - python.exe
    - go.exe
```

- 双面实现为 `CommandWhitelist.Surface` 枚举（ASSEMBLY / PROBE），两面的参数白名单正则、单参长度（1024）与参数数量（64）上限完全一致；任一不合法抛 403002 COMMAND_REJECTED
- **探测面命令绝对路径绑定（P0-1）**：探测命令一律以 `exe.toAbsolutePath()` 提交（杜绝 PATH 首命中错配与冗余进程调用）；`CommandWhitelist.validateProbe` 放行条件 = 逻辑名在探测面表内，或**路径尾段文件名恰为探测面白名单可执行文件**（Windows 文件名不区分大小写），如 `C:\Windows\py.exe` 放行、`C:\Windows\System32\calc.exe` / `C:\evil\java.exe.bat` 拒绝；参数校验强度与逻辑名路径完全一致（绝对路径下注入元字符仍被 403002 拦截）
- `CommandWhitelist.resolveProbeExecutable`：逻辑名映射（`go → go.exe`）；绝对路径按尾段文件名白名单放行，不在白名单返回 null
- 探测执行统一走 `ProcessExecutor.executeProbe(spec, workDir, env, timeoutMs)` 四参重载：显式 2s 超时（P1-2，单次探测不得无限挂起），超时销毁进程并抛 422015；三参重载使用实例默认超时
- 探测面命令均为一击即退的只读查询（`java -XshowSettings:properties -version`、`node -p process.execPath|process.version`、`py -0p`、`python --version`、`go version`），参数全部由 SystemSdkProber 硬编码常量提供，无任何外部输入进入探测命令构造

### 8.6 下载安全

- HTTPS 强制；证书校验；SHA256 校验
- 镜像白名单（防劫持；镜像内容与官方 checksum 不一致返回 502002）
- 断点续传：Range + ETag
- 临时文件 `.part`，校验后重命名
- 磁盘空间检查：≥ 2 倍文件大小

### 8.7 解压安全

- Zip4j 解压
- 路径规范化，拒绝 `..`（Zip-Slip，错误码 422010）
- 压缩比限制：单文件解压后 ≤ 1GB
- 文件数限制：≤ 100000
- 拒绝符号链接、硬链接、设备文件
- 临时目录隔离

### 8.8 审计日志

```java
String prevHash = lastAuditHash;
String content = action + target + result + before + after + createdAt;
String hash = sha256(prevHash + content);
```

- hash 链：`prev_hash` + 当前记录 hash
- 防无意修改，不防恶意篡改
- 保留 90 天；`GET /api/v1/audit` 提供查询与 `chainValid` 校验

### 8.9 密钥

- Token 不落盘（命令行传递的暴露风险见 8.11）
- 数据库密码：首启 SecureRandom 生成，持久化 `{data-root}/config/db.properties`，ACL 仅当前用户（D-006）
- 不存明文 API key（P0 无 API key）

### 8.10 日志脱敏

- 路径：保留最后 2 级，前面替换为 `***`
- Token：完全隐藏
- 用户名：替换为 `<user>`
- 邮箱：替换为 `<email>`

> R51 界定：脱敏专用于**日志落盘、错误响应与诊断包内容**；本机 UI 消费的
> 信息接口（`/system/info` 的 `dataDir`/`sdkRepoDir`、备份/诊断返回路径、
> SDK 列表 `installedPath`）返回**完整真实路径**——同机展示给本机本人，无泄露面。

### 8.11 威胁模型与已接受风险

**信任边界**：单用户桌面；本机其他进程为不可信；网络侧仅 HTTPS 下载源。

| # | 威胁 | 缓解 | 残余风险 |
|---|---|---|---|
| T1 | 本机恶意进程读取 Java 进程命令行获取 Token | 生命周期 = 进程存活；Token 仅鉴权本地 API | **P0 接受（D-016）**；P1 改 stdin / 命名管道握手 |
| T2 | 本机恶意进程直接调用本地 API | 32 字节随机 Token，常量时间比较；无 Token 无法通过 | 低 |
| T3 | DNS 劫持 / 镜像投毒 | HTTPS + 证书校验 + SHA256 + 镜像白名单 + checksum 交叉校验（502002） | 低 |
| T4 | 恶意压缩包（Zip-Slip / 超大解压 / 符号链接） | 8.7 四重检查 | 低 |
| T5 | 恶意项目通过构造参数注入命令 | 参数白名单正则 + 命令白名单 + 超时 + 审计（D-008） | 低 |
| T6 | 同机用户读取 db.properties | ACL 仅当前用户 | 单用户模型内可接受 |

## 9. 核心算法

### 9.1 pom.xml 解析算法

**目标**：从 `pom.xml` 提取语言（JAVA）、版本约束（如 `17`）、来源（`pom.xml`）。

**解析优先级（从高到低）：**

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1 | `<properties><maven.compiler.release>` | 最明确 |
| 2 | `<properties><maven.compiler.target>` + `<source>` | 次明确 |
| 3 | `<properties><java.version>` | SpringBoot 常用 |
| 4 | `<properties><jdk.version>` | 部分项目 |
| 5 | `<parent><version>` 推断 | SpringBoot parent 版本推断 |
| 6 | `<build><plugins>` 中编译器插件 `<release>` | 少数项目 |
| 7 | 未声明 | 返回 UNKNOWN |

**属性求值算法：**

```text
输入：pom.xml（已解析为 DOM）
输出：Map<String, String> properties
步骤：
1. 读取 <properties> 下所有键值对，存入 map
2. 读取 <project> 下内置属性：
   - ${project.version} → <version>
   - ${project.groupId} → <groupId>
   - ${project.artifactId} → <artifactId>
   - ${project.basedir} → 项目根目录
3. 读取系统属性：
   - ${java.version} → 当前 JVM 版本（不覆盖项目声明）
4. 求值循环（最多 10 轮）：
   - 遍历 map，对每个 value 做占位符替换
   - 替换规则：${key} → map.get(key)
   - 若一轮无变化，退出循环
   - 若超过 10 轮仍有未解析占位符，抛 422004
5. 返回求值后的 map
```

**父子 POM 递归算法：**

```text
输入：pomPath
输出：合并后的 EffectivePom
步骤：
1. 解析当前 pom.xml
2. 若存在 <parent>：
   a. 提取 parent 的 groupId、artifactId、version
   b. 查找本地仓库：%USERPROFILE%\.m2\repository\
      {groupId/artifactId/version}/{artifactId}-{version}.pom
   c. 若本地不存在，查找项目同级目录的 ../pom.xml
   d. 若仍不存在，抛 422003
   e. 递归解析 parent（最多 5 层，超过抛 422003）
   f. 合并 parent 的 <properties>、<dependencyManagement>
3. 子 POM 覆盖父 POM 的同名属性
4. 返回合并后的 EffectivePom
```

**循环检测：** 用 `Set<String>` 记录已解析的 GAV，重复出现抛 `422003`。

**Profile 激活算法**（P0 只支持）：

| 激活条件 | 支持 |
|---|---|
| `<activeByDefault>true</activeByDefault>` | 是 |
| `<activation><jdk>` | 是（匹配当前 JVM） |
| `<activation><os><family>` | 是（匹配 WINDOWS） |
| `<activation><property>` | 否（P1） |
| 命令行 `-P` | 否（P1） |

未激活的 profile **不参与**版本约束提取。

**多模块处理（P0 冻结行为）**：P0 **不支持**多模块项目。检测到 `<modules>` 时的冻结行为：

```text
1. 读取首个 <module> 值用于错误提示
2. 直接抛 422001（项目类型无法识别）
3. 用户提示："P0 暂不支持多模块项目，请选择单模块目录"
```

**版本约束标准化：**

| 原始值 | 标准化后 |
|---|---|
| `17` | `17` |
| `17.0` | `17.0` |
| `1.8` | `8` |
| `1.7` | `7` |
| `${java.version}` | 求值后 |
| `[17,18)` | `17`（取左端点） |
| `17.0.9` | `17.0.9` |

**Java 版本特殊处理：** `1.8` → `8`，`1.7` → `7`。

**错误处理：**

| 场景 | 错误码 |
|---|---|
| XML 格式非法 | 422004 |
| 父 POM 未找到 | 422003 |
| 属性无法求值 | 422004 |
| 检测到多模块 | 422001 |
| 无版本声明 | 返回 `UNKNOWN`，不报错 |

**测试用例**（TC-POM-001~010）：覆盖 release 直取 17、`${java.version}` 求值、`1.8`→8、父子合并、父缺失 422003、非法 XML 422004、`${undefined.property}` 422004、多模块 422001、profile activeByDefault 使用/忽略。

### 9.2 版本匹配算法

**目标**：输入项目约束（`17`）、本机已装（`[17.0.9, 21.0.1]`）、可用版本（H2 `sdk_version` 表）；输出推荐版本 + 理由 + 备选版本列表。

```text
输入：constraint, installedVersions[], availableVersions[]
输出：MatchResult { recommended, alternatives[], reason }

步骤 1：解析约束
  - 若 constraint 为精确版本（如 "17.0.9"）：
    精确匹配，跳到步骤 6
  - 若 constraint 为主版本（如 "17"）：
    所有 17.x.y 版本都满足

步骤 2：候选筛选
  candidates = availableVersions.filter(v => matches(v, constraint))

步骤 3：硬性过滤
  candidates = candidates.filter(v =>
    !v.eol &&                              // 排除 EOL
    v.highestCveSeverity != "CRITICAL" &&  // 排除严重 CVE
    v.os == "WINDOWS" &&
    v.arch == "AMD64"
  )

步骤 4：若 candidates 为空
  - 检查是否所有候选都被硬性过滤
  - 若是，抛 422007（仅 EOL 版本满足）或 422008（仅 CVE 版本满足）
  - 若否，抛 422006（无版本满足）

步骤 5：排序
  排序规则（从高到低优先级）：
  a. 本机已装优先（installed == true）
  b. LTS 优先（lts == true）
  c. CVE 数量少的优先（cveCount 升序）
  d. 版本号新的优先（语义化版本降序）
  e. 发行版优先级：Temurin > Zulu > Corretto > 其他

步骤 6：返回
  - recommended = candidates[0]
  - alternatives = candidates[1..5]
  - reason = 生成理由字符串
```

**版本匹配规则：**

```text
matches(version, constraint):
  if constraint 是精确版本:
    return version == constraint
  if constraint 是主版本（"17"）:
    return version.startsWith("17.")
  if constraint 是 Maven range（"[17,18)"）:
    return 17 <= major(version) < 18
  if constraint 是 semver range（">=17 <18"）:
    return 17 <= major(version) < 18
  return false
```

**理由生成：**

```text
recommended = "17.0.9"
reason:
  - 若本机已装： "本机已安装 17.0.9，满足项目约束 17"
  - 若为 LTS： "17.0.9 为 LTS 版本，满足项目约束 17"
  - 若为最新稳定： "17.0.9 为 17.x 最新稳定版，满足项目约束 17"
  - 若无匹配主版本但有替代： "项目约束 17，推荐使用 LTS 版本 17.0.9"
```

**冲突处理：**

| 冲突场景 | 处理 |
|---|---|
| 本机已装 17.0.9，项目约束 17 | 推荐已装，action = REUSE |
| 本机已装 21，项目约束 17 | 推荐 17，action = INSTALL |
| 本机已装 17，项目约束 21 | 推荐 21，action = INSTALL |
| 项目约束 8，但 8 已 EOL | 抛 422007，提示升级 |
| 项目约束 8，但 8 有 CRITICAL CVE | 抛 422008 |

**输出结构：**

```json
{
  "recommended": {
    "language": "JAVA",
    "version": "17.0.9",
    "action": "REUSE",
    "installedPath": "C:\\Users\\user\\.terrascout\\sdks\\java\\17.0.9",
    "reason": "本机已安装 17.0.9，满足项目约束 17"
  },
  "alternatives": [
    {
      "version": "17.0.10",
      "reason": "17.x 最新稳定版"
    },
    {
      "version": "17.0.8",
      "reason": "上一稳定版"
    }
  ]
}
```

**测试用例**（TC-VM-001~010）：覆盖 INSTALL/REUSE、EOL 422007、CRITICAL CVE 422008、无匹配 422006、LTS 优先、Temurin 优先、`1.8` 标准化为 8 等。

> 复杂度说明：候选筛选与排序为线性过滤 + 多键比较排序，匹配规则为常量时间比较；父子 POM 递归深度上限 5 层（循环由 GAV `Set` 检测），属性求值上限 10 轮，均以硬上限保证算法终止。

## 10. 工程目录结构

```text
terrascout/
├── terra-scout-core/                          # 领域模型
│   ├── src/main/java/com/terrascout/orchestrator/core
│   │   ├── domain/                         # 实体
│   │   │   ├── Project.java
│   │   │   ├── SdkVersion.java
│   │   │   ├── SdkInstallRecord.java
│   │   │   ├── Task.java
│   │   │   ├── TaskStep.java
│   │   │   ├── CommandExecution.java
│   │   │   └── AuditLog.java
│   │   ├── enums/
│   │   │   ├── LanguageEnum.java
│   │   │   ├── OsTypeEnum.java
│   │   │   ├── ArchEnum.java
│   │   │   ├── TaskStatusEnum.java
│   │   │   ├── TaskTypeEnum.java
│   │   │   ├── StepStatusEnum.java
│   │   │   ├── ScopeEnum.java
│   │   │   ├── InstallStatusEnum.java       # sdk_install_record.status（database-design §5）
│   │   │   ├── CommandStatusEnum.java       # command_execution.status（database-design §5）
│   │   │   ├── CveSeverityEnum.java         # sdk_version.highest_cve_severity（D-017）
│   │   │   └── ProjectTypeEnum.java         # MAVEN/NPM/GO/PYTHON/MIXED/UNKNOWN（openapi AnalyzeResponse.type）
│   │   ├── error/
│   │   │   ├── TerraScoutError.java
│   │   │   └── TerraScoutException.java
│   │   ├── constant/
│   │   │   └── PathConstants.java           # 统一路径常量（SRS.md §5，禁止第二套硬编码路径）
│   │   └── dto/
│   │       ├── AnalyzeRequest.java
│   │       ├── AnalyzeResponse.java
│   │       ├── ProjectConstraint.java          # openapi ProjectConstraint（parser/version-matcher 复用）
│   │       ├── InstallPlan.java                # openapi InstallPlan
│   │       ├── SdkInstallItem.java             # openapi SdkInstallItem（action: INSTALL/REUSE）
│   │       ├── DependencyInstallItem.java      # openapi DependencyInstallItem（ecosystem: maven/npm）
│   │       ├── CommandSpec.java                # openapi CommandSpec（白名单命令 + 参数列表，D-008）
│   │       ├── ExecuteRequest.java
│   │       ├── ExecuteResponse.java            # openapi ExecuteResponse（taskId + status）
│   │       ├── TaskResponse.java               # openapi TaskDetail；内嵌 TaskStepDetail
│   │       └── ApiResponse.java
│   ├── src/test/java/com/terrascout/orchestrator/core
│   │   ├── error/TerraScoutErrorTest.java     # 46 码唯一性 + HTTP 不变量 + 族计数（D-001 / 3.3.3）
│   │   ├── error/TerraScoutExceptionTest.java # 异常构造 + details 只读
│   │   ├── enums/EnumsSyncTest.java           # 枚举三表同步断言（database-design §5 ↔ assets/openapi ↔ core）
│   │   ├── domain/DomainEntitySyncTest.java   # @Table/@Column 与 database-design §3 逐列对齐
│   │   ├── constant/PathConstantsTest.java    # 路径表与 SRS.md §5 对齐
│   │   └── dto/DtoBeanRoundTripTest.java      # DTO 属性往返 + ApiResponse 工厂
│   └── pom.xml
│
├── terra-scout-parser/                        # 项目解析
│   ├── src/main/java/com/terrascout/orchestrator/parser
│   │   ├── ProjectTypeDetector.java
│   │   ├── PomParser.java
│   │   ├── PackageJsonParser.java          # P1
│   │   ├── PropertyResolver.java
│   │   └── ConstraintExtractor.java
│   ├── src/test/java/com/terrascout/orchestrator/parser
│   │   ├── ProjectTypeDetectorTest.java
│   │   ├── PropertyResolverTest.java
│   │   ├── PomParserTest.java
│   │   ├── ConstraintExtractorTest.java
│   │   └── resources/
│   │       ├── pom-samples/
│   │       │   ├── simple.xml
│   │       │   ├── with-properties.xml
│   │       │   ├── with-parent.xml
│   │       │   ├── with-multi-module.xml
│   │       │   ├── invalid-xml.xml
│   │       │   ├── missing-java-version.xml
│   │       │   └── with-profile.xml
│   └── pom.xml
│
├── terra-scout-download/                      # SDK 下载
│   ├── src/main/java/com/terrascout/orchestrator/download
│   │   ├── Sha256Verifier.java             # SHA-256 校验（422009 CHECKSUM_MISMATCH）
│   │   ├── ResumeableDownloader.java       # Range+ETag 续传（502001/502003/507001）
│   │   ├── ProgressListener.java           # 进度/取消监听接口
│   │   ├── DownloadResult.java             # record 下载结果
│   │   ├── Downloader.java                 # 门面：下载 + SHA-256 校验编排
│   │   ├── DownloadTask.java               # 可取消下载单元（AutoCloseable）
│   │   └── ArchiveExtractor.java           # Zip4j 四重安全检查（422010/507002）
│   ├── src/test/java/com/terrascout/orchestrator/download
│   │   ├── Sha256VerifierTest.java
│   │   ├── ResumeableDownloaderTest.java
│   │   ├── DownloaderTest.java
│   │   ├── DownloadTaskTest.java
│   │   └── ArchiveExtractorTest.java
│   └── pom.xml
│
├── terra-scout-task/                          # 任务引擎
│   ├── src/main/java/com/terrascout/orchestrator/task
│   │   ├── TaskEngine.java                  # 编排：重试 / 回滚 / 心跳自报（409001/500004）
│   │   ├── TaskStateMachine.java            # 9 状态 × 11 事件转移表（纯函数，4.3）
│   │   ├── TaskStepExecutor.java            # 步骤执行器统一接口（5.4）
│   │   ├── TaskStepDefinition.java          # 步骤静态定义 + PROJECT_ASSEMBLE 11 步表（D-018，裁决 R48 四语言）
│   │   ├── TaskStepContext.java             # 步骤执行上下文（5.5）
│   │   ├── HeartbeatMonitor.java            # 心跳超时检测（500003，D-007）
│   │   └── LockManager.java                 # 按 projectId 分锁（公平锁）
│   ├── src/test/java/com/terrascout/orchestrator/task
│   │   ├── TaskStateMachineTest.java        # 转移表全覆盖 + 非法/终态拒绝
│   │   ├── TaskEngineTest.java              # happy path/重试/回滚/锁争用409001/心跳自报
│   │   ├── LockManagerTest.java             # 同锁复用/跨线程互斥/幂等解锁
│   │   ├── HeartbeatMonitorTest.java        # stale 标记/deadline 计算/边界
│   │   ├── TaskStepDefinitionTest.java      # 11 步顺序/超时 D-018/校验
│   │   └── TaskStepContextTest.java         # 链式 setter/map 独立性
│   └── pom.xml
│
├── terra-scout-env/                           # 环境注入
│   ├── src/main/java/com/terrascout/orchestrator/env
│   │   ├── CommandWhitelist.java         # 命令+参数白名单（403002，D-008）
│   │   ├── ProcessExecutor.java          # 命令执行：cmd.exe /c wrap / npm --ignore-scripts / 超时422015 / 裸名按注入 PATH 钉绝对路径（裁决 R48）
│   │   ├── EnvInjector.java              # 注入环境计算（422014 / .devenv 隔离 m2·npm）
│   │   └── EnvScriptGenerator.java       # env.ps1 生成（500002）
│   ├── src/test/java/com/terrascout/orchestrator/env
│   │   ├── CommandWhitelistTest.java     # 元字符全拒绝（TC-010）/命令映射
│   │   ├── ProcessExecutorTest.java      # spawn / 注入拦截于 spawn 前 / 超时422015
│   │   ├── EnvInjectorTest.java          # JAVA_HOME/NODE_HOME/PATH 注入（TC-005）
│   │   └── EnvScriptGeneratorTest.java   # env.ps1 内容 / 单引号转义 / 500002
│   └── pom.xml
│
├── terra-scout-app/                           # SpringBoot 启动
│   ├── src/main/java/com/terrascout/orchestrator
│   │   ├── TerraScoutApplication.java
│   │   ├── config/
│   │   │   ├── WebConfig.java
│   │   │   ├── BeansConfig.java              # @Bean 装配纯 POJO 领域服务（PomParser / ConstraintExtractor，R18）
│   │   │   ├── ReadyPrinter.java             # 就绪后向 stdout 打印 READY <actualPort>（process-management 5.2，供 electron 解析）
│   │   │   ├── ThreadPoolConfig.java
│   │   │   ├── FlywayConfig.java
│   │   │   └── JacksonConfig.java
│   │   ├── controller/
│   │   │   ├── ProjectController.java
│   │   │   ├── TaskController.java          # 含 GET/{taskId}/logs（模块7 Part A 增补）
│   │   │   ├── SdkController.java           # list / install / uninstall / metadata/reload
│   │   │   ├── EnvController.java
│   │   │   ├── SettingsController.java      # GET/PUT settings
│   │   │   ├── AuditController.java
│   │   │   ├── SystemController.java        # info / backup / diagnostic / health-status
│   │   │   └── ShutdownController.java      # POST /shutdown（优雅退出，异步关）
│   │   ├── interceptor/
│   │   │   ├── TokenInterceptor.java
│   │   │   └── TokenHolder.java
│   │   ├── service/
│   │   │   ├── ProjectService.java
│   │   │   ├── TaskService.java             # 含 logs（拼 command_execution）
│   │   │   ├── SdkService.java
│   │   │   ├── SettingsService.java         # 读写 user-settings.json（D-013 白名单键原子写）
│   │   │   ├── BackupService.java           # H2 BACKUP TO + 保留 7 天（D-003）
│   │   │   ├── DiagnosticService.java       # 脱敏 ZIP（排除 db.properties，安全红线）
│   │   │   ├── EnvService.java
│   │   │   └── AuditService.java
│   │   ├── repository/
│   │   │   ├── ProjectRepository.java
│   │   │   ├── TaskRepository.java
│   │   │   ├── TaskStepRepository.java      # 含 findByTaskIdOrderByStepIndex
│   │   │   ├── SdkVersionRepository.java
│   │   │   ├── SdkInstallRecordRepository.java  # 含 findByLanguageAndVersionAndStatus
│   │   │   ├── CommandExecutionRepository.java
│   │   │   └── AuditLogRepository.java
│   │   ├── exception/
│   │   │   └── GlobalExceptionHandler.java
│   │   └── util/
│   │       └── PathSanitizer.java           # 路径脱敏（mask 末两级）
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── sdk-metadata-seed.json          # 首启种子，运行时元数据在外部文件（D-009）
│   │   └── db/migration/
│   │       └── V1__init.sql                # 单一基线，变更一律新增 V<n>（D-005）
│   └── pom.xml
│
├── terra-scout-electron/                      # Electron UI（独立 npm 工程，不进 Maven reactor）
│   ├── package.json                        # electron 30 / react 18 / antd 5 / lucide-react / zustand / @tanstack/react-query / vite / vitest / electron-builder；main=dist/main/index.js
│   ├── tsconfig.json                       # 渲染层 + 测试（typecheck，noEmit）
│   ├── tsconfig.main.json                  # 主进程 + preload 编译（CommonJS → dist/）
│   ├── vite.config.ts                      # 渲染层构建（root=src/renderer → dist/renderer，base './'；CSS Modules + 路由级懒加载）
│   ├── vitest.config.ts                    # 进程/工具单测门禁
│   ├── scripts/
│   │   ├── dev.ps1                         # 本机联调（TERRA_SCOUT_JAR=后端 target jar，JAVA_BIN=java）
│   │   ├── build-jre.ps1                   # mvn package → jdeps 校验 → jlink 12 模块（release 8.2/8.3，D-010）
│   │   └── build-app.ps1                   # npm run build + electron-builder --win nsis（resources 带 jar+jre）
│   ├── resources/                          # 打包产物（.gitignore，build-jre/build-app 填充）
│   │   ├── terrascout.jar
│   │   └── jre/                            # 裁剪 JRE（bin/java.exe）
│   ├── src/
│   │   ├── main/
│   │   │   ├── index.ts                    # 生命周期：window(contextIsolation/sandbox)、CSP、before-quit 优雅关闭
│   │   │   ├── java-process.ts             # spawn Java / READY <port> / 崩溃重启 ≤3次·2s / 优雅退出 / resolveJarPath
│   │   │   ├── kernel-info.ts              # token crypto.randomBytes(32).b64 仅内存(D-016) + 端口/pid + electron.log
│   │   │   └── ipc-handlers.ts             # get-info/get-token/restart/select-directory/notify-ready 广播
│   │   ├── preload/
│   │   │   └── index.ts                    # contextBridge → window.kernel（getToken/onReady/onEvent/restart/selectDirectory）
│   │   └── renderer/
│   │       ├── index.html                  # lang/color-scheme + global.css 引入
│   │       ├── index.tsx                   # 挂载 + QueryClientProvider(TanStack Query) + ConfigProvider(antd zhCN + 令牌主题)
│   │       ├── App.tsx                     # HashRouter + 布局壳（顶栏56/侧栏240/底栏32，spec §3.1）+ Root 包装 + 懒加载路由
│   │       ├── app.module.css              # 布局壳样式（令牌化）
│   │       ├── global.d.ts / styles.d.ts   # window.kernel 类型 + *.module.css 声明
│   │       ├── styles/
│   │       │   ├── tokens.css              # 设计令牌（spec §13.3 完整版，含深色模式）
│   │       │   ├── reset.css               # CSS 重置
│   │       │   └── global.css              # 全局样式 + focus-visible + prefers-reduced-motion + 进度条/骨架屏(scaleX/opacity)
│   │       ├── store/
│   │       │   └── kernel.ts               # Zustand：内核就绪/崩溃/GIVEUP 等跨页全局客户端状态（分工：服务端数据不进此 store）
│   │       ├── api/
│   │       │   ├── types.ts                # 与 api-spec.md 对齐的 TS 类型
│   │       │   ├── client.ts               # fetch + X-TerraScout-Token + code==HTTP/1000 解码 + 401001→重启提示
│   │       │   └── errorMap.ts             # 错误码 → 文案/颜色/按钮（coding-standards.md §8）
│   │       ├── pages/
│   │       │   ├── Home.tsx / ProjectDetail.tsx / Tasks.tsx / TaskDetail.tsx / Sdks.tsx / Settings.tsx / About.tsx  # 7 页（各独立懒加载 chunk）
│   │       │   └── pages.module.css        # 页面通用令牌化样式
│   │       ├── components/
│   │       │   ├── Common.tsx + common.module.css            # PageLoading(骨架屏,opacity) / PageTitle
│   │       │   ├── ProjectCard.tsx + project-card.module.css # 项目卡片（La-eroupe 主机色/按钮）
│   │       │   ├── StepProgress.tsx                          # 步骤 Steps
│   │       │   ├── LogViewer.tsx + log-viewer.module.css     # 日志面板（aria-live/级别着色/等宽）
│   │       │   ├── TaskProgress.tsx                          # 进度条（transform: scaleX，spec §4.7）
│   │       │   ├── EmptyState.tsx + states.module.css        # 空状态（spec §5.2）
│   │       │   └── ErrorState.tsx + states.module.css        # 错误状态（spec §5.3/错误码映射）
│   │       └── lib/
│   │           └── id.ts                  # uuid / formatTime / formatBytes
│   └── test/                              # vitest：java-process / kernel-info / errorMap / client（18 测试）
│       ├── java-process.test.ts
│       ├── kernel-info.test.ts
│       ├── errorMap.test.ts
│       └── client.test.ts
│
├── pom.xml                                 # 父 POM
└── README.md
```