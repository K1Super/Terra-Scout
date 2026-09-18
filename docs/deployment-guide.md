# Terra Scout 部署指南

> 文档版本 V3.0 | 更新日期 2026-09-18

本文档覆盖从构建、jlink 裁剪、Windows 打包签名、安装卸载、配置参考、第三方依赖合规到发布检查清单的完整发布链路。

## 1. 构建流程

### 1.1 后端质量门禁（发布前必过）

```bash
mvn clean verify
```

门禁含义：编译 + Checkstyle 0 违规 + 测试 0 失败 + JaCoCo LINE ≥ 80%（全 reactor BUILD SUCCESS）。

> 注意：必须用 `mvn clean verify`，`mvn clean package` **不会**触发绑定在 verify 阶段的 JaCoCo 门禁。

### 1.2 后端打包

```bash
mvn clean package -DskipTests
```

### 1.3 校验 jlink 模块清单

```bash
jdeps --ignore-missing-deps --print-module-deps target/terrascout.jar
```

（与第 2 节基线比对，防依赖变化后清单失效。）

### 1.4 jlink 裁剪 JRE

```bash
jlink --add-modules java.base,java.sql,java.xml,java.naming,java.management,java.net.http,java.transaction.xa,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata \
      --output target/jre \
      --strip-debug --compress=2 --no-header-files --no-man-pages
```

### 1.5 前端构建

electron 为独立 npm 工程（不进 Maven reactor），模块实际目录为 `terra-scout-electron`：

```bash
cd terra-scout-electron && npm run build
```

类型检查与测试（Part B 门禁）：

```bash
tsc --noEmit
vite build
npm test
```

### 1.6 打包

```bash
electron-builder --win nsis
```

## 2. 运行时裁剪（jlink 基线清单）

必须包含的模块（与第 1 节 jlink 命令严格一致，D-010）：

- `java.base`
- `java.sql`（H2 JDBC）
- `java.xml`（Spring XML 解析）
- `java.naming`（JNDI/数据源）
- `java.management`（Spring Boot 管理）
- `java.net.http`（下载器）
- `java.transaction.xa`（H2 XA）
- `jdk.crypto.ec`（HTTPS EC 密码套件）
- `jdk.unsupported`（H2 依赖 sun.misc）
- `jdk.zipfs`（ZIP 文件系统）
- `jdk.charsets`（中文 Windows 字符集）
- `jdk.localedata`（区域数据）

> 清单以 `jdeps --ignore-missing-deps --print-module-deps target/terrascout.jar` 实测为准；CI 中校验实测结果与基线一致，新增依赖导致清单变化时必须同步更新本节与第 1 节命令。

## 3. Windows 打包与签名（NSIS）

- 打包目标：`electron-builder --win nsis`，产出 NSIS 安装器。
- **硬性经验 1**：NSIS 打包需在 **Administrator PowerShell** 中执行，以处理 winCodeSign 中 darwin/ 符号链接问题。
- **硬性经验 2**：electron-builder 二进制下载使用镜像解决 GitHub CDN 连接问题：

```bash
ELECTRON_BUILDER_BINARIES_MIRROR=https://npmmirror.com/mirrors/electron-builder-binaries/
```

## 4. 安装与卸载

### 4.1 安装包结构

```text
TerraScout-Setup-0.1.0.exe
  ├── TerraScout.exe（Electron）
  ├── resources/
  │   ├── app.asar（前端）
  │   ├── terrascout.jar（Java）
  │   └── jre/（裁剪 JRE）
  └── ...
```

### 4.2 卸载策略

- 卸载时保留 `%USERPROFILE%\.terrascout`（用户数据）。
- 提供"彻底清理"选项。
- 项目 `.devenv` 不删除（属于用户项目）。

### 4.3 回滚方案

- 保留上一版本安装包。
- 卸载新版本，安装旧版本。
- 数据库兼容：新版本写入的字段旧版本可能不识别，需备份。

### 4.4 内测发布

- 未签名，用户需手动允许运行。
- 仅限内测用户。
- 收集反馈，修复后进入 P1。

### 4.5 卸载语义（实现侧事实）

- SDK 卸载走物理目录删除，遵循：最后引用删除 / 共享保留 / 非标准路径护栏跳过（R43/R44 单测覆盖）。
- 已安装 SDK 的系统探测项无卸载语义（无 recordId 不渲染卸载按钮）。

## 5. 配置参考

### 5.1 配置优先级（D-013）

```text
内置默认（application.yml，打包只读）
  < {data-root}/config/user-settings.json（运行时可变项）
  < {data-root}/config/db.properties（仅 terrascout.db.password）
  < 命令行参数（Electron 传入：端口 / Token / 数据目录）
```

### 5.2 application.yml（完整、无重复键）

```yaml
server:
  address: 127.0.0.1
  port: 0                              # 0 = 系统分配

spring:
  config:
    import: "optional:file:${terrascout.data-dir}/config/db.properties"
  datasource:
    url: jdbc:h2:file:${terrascout.data-dir}/db/terrascout;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: terrascout
    password: ${terrascout.db.password}    # 首启生成并持久化，见 database-design.md §1
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false

logging:
  level:
    root: INFO
    com.terrascout: DEBUG
  file:
    name: ${terrascout.data-dir}/logs/terrascout.log
  logback:
    rollingpolicy:
      max-file-size: 10MB
      max-history: 7
      total-size-cap: 100MB

terrascout:
  data-dir: ${user.home}/.terrascout
  token-length: 32                     # bytes
  shutdown-timeout-ms: 5000
  sdk:
    install-base-dir: ${terrascout.data-dir}/sdks       # SDK 仓库，全局共享（D-004）
    metadata-file: ${terrascout.data-dir}/config/sdk-metadata.json   # 外部文件（D-009）
    metadata-seed: classpath:sdk-metadata-seed.json     # 首启种子
  project:
    isolation-dir-name: .devenv
    max-path-length: 260
  download:
    timeout-ms: 60000
    connect-timeout-ms: 10000
    max-retry: 3
    retry-backoff-ms: 2000
    chunk-size: 8192
    mirror-whitelist:
      - https://repo.huaweicloud.com
      - https://mirrors.tuna.tsinghua.edu.cn
  task:
    thread-pool-size: 4
    queue-capacity: 100
    heartbeat-interval-ms: 5000
    heartbeat-timeout-ms: 60000
    max-retry: 3
  command:
    timeout-ms: 600000
    whitelist:
      - mvn.cmd
      - npm.cmd
      - java.exe
      - node.exe
    arg-pattern: '^[A-Za-z0-9@+=:,._/\\-]+$'    # 白名单正则（D-008）
  audit:
    retention-days: 90
    hash-algorithm: SHA-256
  db:
    backup-on-startup: true
    backup-retention-days: 7
  ai:
    enable: false
```

### 5.3 首启引导（含 H2 密码首启生成机制）

| 资源 | 行为 |
|---|---|
| `{data-root}/config/db.properties` | 不存在 → SecureRandom 生成 24 字节 Base64，写入并设 ACL 仅当前用户（D-006） |
| `{data-root}/config/sdk-metadata.json` | 不存在 → 从 jar 内 `sdk-metadata-seed.json` 提取复制（D-009） |
| `{data-root}` 目录树 | 不存在 → 自动创建 db / logs / config / sdks / db\backup / diagnostics |

H2 密码首启生成机制（实现侧）：`StartupGuard.ensureDatabasePassword()` 有则读复用，否则 SecureRandom 生成 24 字节 Base64 持久化到 `config/db.properties`（追加式 ACL 收紧，D-006）。密码仅由 `db.properties`（仅 `terrascout.db.password` 键）外部持久化，不进 application.yml、不进诊断包（DiagnosticService 排除 db.properties 安全红线）。

### 5.4 运行时可变项（user-settings.json）

由 `GET/PUT /api/v1/settings` 读写（api-spec.md §4.15），热生效：

| 键 | 热生效范围 |
|---|---|
| `download.mirror` | 下一次下载起 |
| `download.timeoutMs` / `maxRetry` | 下一次下载起 |
| `commandTimeoutMs` | 下一次命令执行起 |
| `logLevel` | Logback 立即生效 |
| `aiEnabled` | 立即生效 |

**需重启生效**（改自 application.yml 侧，不在 settings API 暴露）：`thread-pool-size`、`queue-capacity`、`heartbeat-*`、`data-dir`、`install-base-dir`、`max-path-length`、镜像白名单本身（白名单仅防劫持，增删需重启）。

### 5.5 关键说明

- `server.port: 0`：系统分配空闲端口，实际端口经 Java stdout `READY <port>` 传给 Electron（high-level-design.md §7）。
- `arg-pattern`：覆盖 cmd.exe 全部元字符 `& | ; > < ` $ % ^ ( ) ! " '`，任何不匹配的参数直接拒绝（403002），**禁止退回黑名单方案**。
- `install-base-dir` 指向 SDK 仓库（全局共享），项目隔离域由 `isolation-dir-name` 决定，二者职责不同（D-004）。

## 6. 第三方依赖与许可证合规

### 6.1 第三方依赖清单

| 依赖 | 版本 | 用途 | 许可证 |
|---|---|---|---|
| SpringBoot | 3.2.x | Web 框架 | Apache 2.0 |
| H2 | 2.2.x | 数据库 | MPL 2.0 / EPL 1.0 |
| Flyway | 9.x | 数据库迁移 | Apache 2.0 |
| OkHttp | 4.12.x | HTTP 客户端 | Apache 2.0 |
| Zip4j | 2.11.x | 解压 | Apache 2.0 |
| Commons-IO | 2.15.x | 文件操作 | Apache 2.0 |
| Jackson | 2.16.x | JSON | Apache 2.0 |
| SLF4J + Logback | 2.0.x / 1.4.x | 日志 | EPL 1.0 / LGPL 2.1 |
| JUnit 5 | 5.10.x | 测试 | EPL 2.0 |
| Mockito | 5.x | 测试 | MIT |
| AssertJ | 3.25.x | 测试 | Apache 2.0 |
| Awaitility | 4.2.x | 测试（异步轮询等待） | Apache 2.0 |
| MockWebServer | 4.12.x（OkHttp 生态） | 测试（网络/断点续传模拟） | Apache 2.0 |
| Electron | 30.x | UI | MIT |
| React | 18.x | UI | MIT |
| Ant Design | 5.x | UI | MIT |

### 6.2 许可证合规

| 项 | 要求 |
|---|---|
| JDK 发行版 | 只使用 OpenJDK（Temurin / Zulu），不使用 Oracle JDK |
| 下载的 SDK | 遵守各 SDK 许可证 |
| 第三方库 | 全部为 Apache 2.0 / MIT / EPL，允许商业使用 |
| SBOM | P1 生成 CycloneDX |

> 依赖最小化原则（SRS.md D-012）：不为单一工具类引入新依赖。
> 例如：不引入 Guava 仅为 `ThreadFactoryBuilder`，统一使用自研 `NamedThreadFactory`（high-level-design.md §5）。
> 网络类测试禁止直连公网，一律使用 MockWebServer（testing.md §3）。

## 7. 发布检查清单

### 7.1 代码检查

| 项 | 验证方法 | 负责人 | 证据 |
|---|---|---|---|
| 编译通过 | `mvn clean package` | 开发 | CI 日志 |
| 单元测试通过 | `mvn test` | 开发 | Surefire 报告 |
| 集成测试通过 | `mvn verify` | 开发 | Failsafe 报告 |
| 覆盖率 ≥ 75% | `mvn jacoco:report` | 开发 | JaCoCo HTML |
| 无高危漏洞 | `mvn dependency-check:check` | 安全 | OWASP 报告 |
| SonarQube 通过 | `mvn sonar:sonar` | 开发 | Sonar 报告 |
| 代码规范通过 | `mvn checkstyle:check` | 开发 | Checkstyle 报告 |

### 7.2 功能检查

| 项 | 验证方法 | 负责人 | 证据 |
|---|---|---|---|
| 项目导入 | 手工测试 | 测试 | 截图 |
| SDK 下载 | 手工测试 | 测试 | 截图 |
| SHA256 校验 | 手工测试 | 测试 | 日志 |
| 断点续传 | 手工测试 | 测试 | 日志 |
| 项目隔离 | 手工测试 | 测试 | 目录截图 |
| 环境注入 | 手工测试 | 测试 | `mvn -version` |
| 全局未变 | 手工测试 | 测试 | `echo $env:JAVA_HOME` |

### 7.3 混沌检查

| 项 | 验证方法 | 负责人 | 证据 |
|---|---|---|---|
| 断网 | 下载中断网 | 测试 | 日志 |
| 磁盘满 | 填充磁盘 | 测试 | 错误码 |
| 进程被杀 | kill Java | 测试 | 恢复日志 |
| 电源中断 | 强制关机 | 测试 | H2 完整性 |
| 权限拒绝 | 只读目录 | 测试 | 错误提示 |

### 7.4 发布检查

| 项 | 验证方法 | 负责人 | 证据 |
|---|---|---|---|
| 版本号正确 | 检查 pom.xml | 开发 | 文件 |
| 安装包生成 | electron-builder | 开发 | 安装包 |
| 安装成功 | 干净 VM 安装 | 测试 | 截图 |
| 运行成功 | 启动工具 | 测试 | 截图 |
| 卸载干净 | 控制面板卸载 | 测试 | 截图 |
| 数据保留 | 检查 `.terrascout` | 测试 | 截图 |
| 回滚验证 | 卸载新版装旧版 | 测试 | 截图 |

> 任何一项未通过，不得发布。