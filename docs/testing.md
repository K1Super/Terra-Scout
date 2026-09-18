# Terra Scout 测试说明

> 文档版本 V3.0 | 更新日期 2026-09-18 | 适用范围 P0

## 1. 测试策略

测试分为四个层级，各层目标与范围如下（依据 SRS.md §6 工程治理要求）。

| 层级 | 目标 | 范围与方式 | 介入时间 |
|---|---|---|---|
| 单元测试 | 各模块逻辑正确性 | 每模块随代码提交；模块覆盖率 ≥ 75%（JaCoCo） | 随时 |
| 集成测试 | 端到端装配流程正确 | `@SpringBootTest(RANDOM_PORT)`，H2 原生模式（与生产一致，D-011），`@TempDir` 隔离文件系统，Awaitility 异步等待 | W6 起 |
| 网络测试 | 下载/续传/校验行为正确 | MockWebServer 模拟镜像 / 断点续传 / 502001 / 422009，禁止测试直连公网 | W6 起 |
| 混沌测试 | 异常场景下的健壮性 | 断网 / 磁盘满 / kill 进程 / 断电 / 只读目录 | W8 起 |

指标：覆盖率 ≥ 75%，SDK 安装成功率 ≥ 98%，断点续传成功率 ≥ 98%，回滚成功率 100%。

## 2. 测试环境与工具

### 2.1 测试库与数据库

- 测试库 = H2 原生模式（与生产一致），版本 2.2.x；禁用 Testcontainers（P0 零 Docker）；移除 `MODE=MySQL`（D-011）。
- 原则：**测试通过必须代表生产行为**（需求基线原则，D-011）。
- 数据源：H2 文件模式指向 `@TempDir`（与生产同为 H2 原生模式，无 MODE=MySQL）。
- 迁移：classpath:db/migration，与生产使用同一套 Flyway 脚本。

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 数据源：H2 文件模式指向 @TempDir（与生产同为 H2 原生模式，无 MODE=MySQL）
// 禁止 Testcontainers / Docker（P0 卖点即零外部依赖）
```

### 2.2 生产与测试环境一致性

| 项 | 生产 | 测试 | 一致性 |
|---|---|---|---|
| 数据库 | H2 2.2.x 原生文件模式 | 同版本原生模式，路径指向 `@TempDir` | ✅ |
| 迁移 | classpath:db/migration | 同一套 Flyway 脚本 | ✅ |
| 网络 | 公网 HTTPS 下载 | MockWebServer（本地回环） | 模拟 |

### 2.3 网络模拟（MockWebServer）

所有下载类测试基于 MockWebServer（OkHttp 生态，Apache 2.0）。禁止测试直连公网。

### 2.4 测试技术栈

JUnit 5.10 / Mockito 5 / AssertJ 3.25 / Awaitility 4.2。

## 3. 测试数据与样本

### 3.1 测试样本

```text
terra-scout-parser/src/test/resources/pom-samples/
├── simple.xml                    # 单模块，无属性
├── with-properties.xml           # 有 java.version 属性
├── with-parent.xml               # 有 parent
├── with-multi-module.xml         # 多模块 → 期望 422001（P0 冻结行为）
├── invalid-xml.xml               # 非法 XML → 期望 422004
├── missing-java-version.xml      # 无版本声明 → 期望约束 UNKNOWN，不报错
└── with-profile.xml              # 有 profile
```

### 3.2 网络模拟场景（MockWebServer）

| 场景 | 模拟方法 | 断言 |
|---|---|---|
| 正常下载 | 200 + 完整 body + 正确 SHA256 | 安装成功 |
| 中途断连 | 半途关闭连接（`.part` 保留） | 断点续传成功（TC-003） |
| SHA256 损坏 | 200 + 错误内容 | 422009 |
| 源不可达 | 不启动 server / 连接拒绝 | 502001 |
| 不支持 Range | 200 覆盖整个文件 | 502003 |
| 镜像 checksum 不一致 | 官方源与镜像返回不同内容 | 502002 |

### 3.3 测试数据清理

```java
@AfterEach
public void cleanup() throws IOException {
    if (Files.exists(testProjectDir)) {
        FileUtils.deleteDirectory(testProjectDir.toFile());
    }
}
```

### 3.4 造数规则

- 样本项目通过复制 `pom-samples/` 下文件到 `@TempDir` 生成，装配后验证 `.devenv` 与 `.devenv/env.ps1` 存在。
- 网络故障通过 MockWebServer 半途关闭连接、返回错误内容、拒绝连接等方式构造，不依赖真实公网。
- 源文档未提及 AI 生成测试数据的能力，P0 不采用。

## 4. 测试门禁与验收

### 4.1 测试总数说明

源文档未约定 `mvn clean verify` 的测试总数、也未约定前端 typecheck / build / 测试的具体数字，仅约定以下门禁指标与命令，此处如实保留、不补充新数字：

| 项 | 要求 | 工具 |
|---|---|---|
| 编译 | `mvn clean package` 通过 | Maven |
| 单元测试 | 模块覆盖率 ≥ 75% | JaCoCo |
| 代码规范 | Checkstyle 通过 | Checkstyle |
| 漏洞 | 无高危 | OWASP dependency-check |
| 静态质量 | 无 Blocker/Critical | SonarQube |
| PR | 必须含测试 + 引用文档章节号 | Git 规范见 coding-standards.md |

### 4.2 验收测试环境

| 项 | 配置 |
|---|---|
| OS | Windows 11 Pro 22H2 |
| CPU | 4 核 |
| 内存 | 8GB |
| 磁盘 | 100GB SSD |
| 网络 | 可访问公网 |
| 快照 | 测试前恢复 |

### 4.3 验收用例

验收用例 TC-001 ~ TC-014 完整保留在 SRS.md 第 7.1 节，本节仅做映射；验收执行须覆盖：

- 项目导入分析（TC-001）、SDK 安装（TC-002）、断点续传（TC-003）、项目隔离（TC-004）、进程级环境注入（TC-005）、任务暂停与恢复（TC-006）、任务失败回滚（TC-007）、崩溃恢复（TC-008）、磁盘空间不足（TC-009）、命令注入防护（TC-010）、Zip-Slip 防护（TC-011）、Token 鉴权（TC-012）、审计日志（TC-013）、AI 开关（TC-014）。

### 4.4 混沌测试

混沌场景仅在 W8 混沌阶段于专用环境执行。方法如下：

| 测试 | 方法 | 期望 |
|---|---|---|
| 断网 | 下载中断网 | 断点续传 |
| 磁盘满 | 填充磁盘 | 507001 |
| 进程被杀 | kill Java | 崩溃恢复 |
| 电源中断 | 强制关机 | H2 完整，任务可恢复 |
| 权限拒绝 | 只读目录 | 明确提示 |

### 4.5 验收流程与出厂判定

1. 测试前恢复环境快照（见 4.2）。
2. 依次执行 TC-001 ~ TC-014 验收用例，全部通过。
3. 执行混沌测试（4.4），全部通过。
4. 指标达标：

   - SDK 安装成功率 ≥ 98%
   - 断点续传成功率 ≥ 98%
   - 回滚成功率 100%
   - 覆盖率 ≥ 75%

5. 安全测试通过。
6. 文档齐全。

以上任何一项未通过不得发布。

## 5. 编写与运行指南

### 5.1 集成测试骨架

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ProjectAssembleIT {

    @TempDir
    Path testProjectDir;

    @Test
    public void testFullAssembleFlow() {
        // 1. 准备测试项目（复制 pom-samples/simple.xml）
        Path projectDir = prepareTestProject();
        // 2. 调用分析接口
        AnalyzeResponse analyze = restTemplate.postForObject(
            "/api/v1/project/analyze",
            new AnalyzeRequest(projectDir.toString(), null),
            AnalyzeResponse.class);
        assertThat(analyze.getCode()).isEqualTo(200000);
        assertThat(analyze.getData().getConstraints()).hasSize(1);
        // 3. 执行装配（异步语义由 taskId 表达，D-002）
        ExecuteResponse execute = restTemplate.postForObject(
            "/api/v1/project/execute",
            new ExecuteRequest(analyze.getData().getPlan().getPlanId(), true, UUID.randomUUID().toString()),
            ExecuteResponse.class);
        assertThat(execute.getCode()).isEqualTo(200000);
        // 4. 轮询任务（Awaitility）
        String taskId = execute.getData().getTaskId();
        await().atMost(10, TimeUnit.MINUTES).until(() -> {
            TaskResponse task = restTemplate.getForObject("/api/v1/task/" + taskId, TaskResponse.class);
            return "SUCCESS".equals(task.getData().getStatus());
        });
        // 5. 验证
        assertThat(Files.exists(projectDir.resolve(".devenv"))).isTrue();
        assertThat(Files.exists(projectDir.resolve(".devenv/env.ps1"))).isTrue();
    }
}
```

### 5.2 运行方式

- 编译/打包门禁：`mvn clean package` 通过（模块 DoD 要求）。
- 集成测试 W6 开始、混沌测试 W8 开始。
- 单元测试随各模块代码提交执行；下载类测试必须基于 MockWebServer，禁止直连公网。

### 5.3 网络依赖测试约定

- **禁止**单元/集成测试直连公网（公网状态影响 CI 稳定性，且无法构造故障）。
- 所有下载类测试基于 MockWebServer（OkHttp 生态，Apache 2.0，已列入 deployment-guide.md §6.1）。
- 混沌场景（真实断网/磁盘满/kill）仅在 W8 混沌阶段于专用环境执行（本节 4.4）。