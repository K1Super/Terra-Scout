package com.terrascout.orchestrator.core.constant;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

import com.terrascout.orchestrator.core.enums.LanguageEnum;

/**
 * 统一路径常量（master-plan.md §4 唯一路径口径，禁止第二套硬编码路径）。
 *
 * <p>设计约束：
 * <ul>
 *   <li>本类只负责<b>路径组装</b>；规范化与安全校验（toRealPath + 目标前缀校验）在使用方执行（安全红线 3）；</li>
 *   <li>数据根默认 {@code %USERPROFILE%\.terrascout}（D-003），可被系统属性
 *       {@code terrascout.data-dir}（{@link #DATA_DIR_PROPERTY}）覆盖，由 app 层配置绑定传入；</li>
 *   <li>SDK 仓库全局共享 {@code {data-root}\sdks\{language}\{version}}（D-004）；</li>
 *   <li>项目隔离域 {@code {projectRoot}\.devenv}（D-004）。</li>
 * </ul>
 */
public final class PathConstants {

    /** 覆盖数据根的系统属性键（app 层与 Spring relaxed binding 共用）。 */
    public static final String DATA_DIR_PROPERTY = "terrascout.data-dir";

    /** 默认数据根目录名（D-003：唯一数据根 {@code %USERPROFILE%\.terrascout}）。 */
    public static final String DEFAULT_DATA_DIR_NAME = ".terrascout";

    // ==================== 数据根子目录 ====================
    /** 数据库目录：{data-root}\db。 */
    public static final String DIR_DB = "db";
    /** 数据库备份目录：{data-root}\db\backup。 */
    public static final String DIR_BACKUP = "backup";
    /** 日志目录：{data-root}\logs。 */
    public static final String DIR_LOGS = "logs";
    /** 配置目录：{data-root}\config。 */
    public static final String DIR_CONFIG = "config";
    /** SDK 仓库目录：{data-root}\sdks（D-004，全局共享）。 */
    public static final String DIR_SDKS = "sdks";
    /** SDK 安装落位票根文件（写入安装目录内，卸载物理删除的护栏凭据）。 */
    public static final String FILE_SDK_MARKER = ".terrascout-sdk-marker";
    /** 诊断包目录：{data-root}\diagnostics。 */
    public static final String DIR_DIAGNOSTICS = "diagnostics";

    // ==================== 数据根文件名 ====================
    /** H2 文件库：{data-root}\db\terrascout.mv.db。 */
    public static final String FILE_DB = "terrascout.mv.db";
    /** 数据库密码文件：{data-root}\config\db.properties（D-006，ACL 仅当前用户）。 */
    public static final String FILE_DB_PROPERTIES = "db.properties";
    /** 运行时设置：{data-root}/config/user-settings.json（D-013）。 */
    public static final String FILE_USER_SETTINGS = "user-settings.json";
    /** SDK 元数据：{data-root}\config\sdk-metadata.json（D-009，外部文件支持热重载）。 */
    public static final String FILE_SDK_METADATA = "sdk-metadata.json";
    /** 内核日志文件名。 */
    public static final String FILE_LOG_KERNEL = "terrascout.log";
    /** Electron 日志文件名。 */
    public static final String FILE_LOG_ELECTRON = "electron.log";
    /** 备份文件后缀。 */
    public static final String BACKUP_SUFFIX = ".mv.db";

    // ==================== 项目隔离域（D-004） ====================
    /** 隔离域目录名：{projectRoot}\.devenv。 */
    public static final String ISOLATION_DIR_NAME = ".devenv";
    /** 隔离域内 Maven 本地仓库目录名。 */
    public static final String ISOLATION_M2 = "m2";
    /** 隔离域内 npm 缓存目录名。 */
    public static final String ISOLATION_NPM_CACHE = "npm-cache";
    /** 隔离域内 Go 模块缓存目录名（GOMODCACHE）。 */
    public static final String ISOLATION_GO_CACHE = "go-cache";
    /** 环境脚本文件名（ADR-007：进程级注入入口）。 */
    public static final String ISOLATION_ENV_SCRIPT = "env.ps1";

    private PathConstants() {
    }

    /** 默认数据根：{@code user.home/.terrascout}。 */
    public static Path defaultDataRoot() {
        return Paths.get(System.getProperty("user.home"), DEFAULT_DATA_DIR_NAME);
    }

    /** 读取数据根（可被 {@code terrascout.data-dir} 系统属性覆盖，未设置用默认值）。 */
    public static Path dataRoot() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        return override == null || override.isBlank() ? defaultDataRoot() : Paths.get(override);
    }

    /** H2 文件库路径：{data-root}\db\terrascout.mv.db。 */
    public static Path databaseFile(Path dataRoot) {
        return requireNonNull(dataRoot).resolve(DIR_DB).resolve(FILE_DB);
    }

    /** 数据库密码文件：{data-root}\config\db.properties。 */
    public static Path dbProperties(Path dataRoot) {
        return requireNonNull(dataRoot).resolve(DIR_CONFIG).resolve(FILE_DB_PROPERTIES);
    }

    /** 运行时设置：{data-root}/config/user-settings.json。 */
    public static Path userSettings(Path dataRoot) {
        return requireNonNull(dataRoot).resolve(DIR_CONFIG).resolve(FILE_USER_SETTINGS);
    }

    /** SDK 元数据：{data-root}\config\sdk-metadata.json。 */
    public static Path sdkMetadata(Path dataRoot) {
        return requireNonNull(dataRoot).resolve(DIR_CONFIG).resolve(FILE_SDK_METADATA);
    }

    /** 日志目录：{data-root}\logs。 */
    public static Path logsDir(Path dataRoot) {
        return requireNonNull(dataRoot).resolve(DIR_LOGS);
    }

    /** 内核日志：{data-root}\logs\terrascout.log。 */
    public static Path kernelLog(Path dataRoot) {
        return logsDir(dataRoot).resolve(FILE_LOG_KERNEL);
    }

    /** SDK 仓库：{data-root}\sdks（D-004 全局共享）。 */
    public static Path sdkRepository(Path dataRoot) {
        return requireNonNull(dataRoot).resolve(DIR_SDKS);
    }

    /** SDK 安装目录：{data-root}\sdks\{language}\{version}（D-004；目录名用小写语言名）。 */
    public static Path sdkHome(Path dataRoot, LanguageEnum language, String version) {
        Objects.requireNonNull(language, "language 不能为 null");
        Objects.requireNonNull(version, "version 不能为 null");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version 不能为空");
        }
        return sdkRepository(dataRoot)
                .resolve(language.name().toLowerCase(Locale.ROOT))
                .resolve(version);
    }

    /** 数据库备份目录：{data-root}\db\backup。 */
    public static Path backupDir(Path dataRoot) {
        return requireNonNull(dataRoot).resolve(DIR_DB).resolve(DIR_BACKUP);
    }

    /** 启动备份文件：{data-root}\db\backup\terrascout-{yyyyMMddHHmmss}.mv.db（ddl-migration 2.5）。 */
    public static Path backupFile(Path dataRoot, String timestamp) {
        Objects.requireNonNull(timestamp, "timestamp 不能为 null");
        if (timestamp.isBlank()) {
            throw new IllegalArgumentException("timestamp 不能为空");
        }
        return backupDir(dataRoot).resolve(FILE_DB.replace(BACKUP_SUFFIX, "") + "-" + timestamp + BACKUP_SUFFIX);
    }

    /** 诊断包目录：{data-root}\diagnostics。 */
    public static Path diagnosticsDir(Path dataRoot) {
        return requireNonNull(dataRoot).resolve(DIR_DIAGNOSTICS);
    }

    /** 项目隔离域：{projectRoot}\.devenv（D-004）。 */
    public static Path isolationRoot(Path projectRoot) {
        return requireNonNull(projectRoot).resolve(ISOLATION_DIR_NAME);
    }

    /** 隔离域 Maven 本地仓库：{projectRoot}\.devenv\m2。 */
    public static Path isolationM2(Path projectRoot) {
        return isolationRoot(projectRoot).resolve(ISOLATION_M2);
    }

    /** 隔离域 npm 缓存：{projectRoot}\.devenv\npm-cache。 */
    public static Path isolationNpmCache(Path projectRoot) {
        return isolationRoot(projectRoot).resolve(ISOLATION_NPM_CACHE);
    }

    /** 隔离域 Go 模块缓存：{projectRoot}\.devenv\go-cache（GOMODCACHE）。 */
    public static Path isolationGoCache(Path projectRoot) {
        return isolationRoot(projectRoot).resolve(ISOLATION_GO_CACHE);
    }

    /** 隔离域环境脚本：{projectRoot}\.devenv\env.ps1。 */
    public static Path isolationEnvScript(Path projectRoot) {
        return isolationRoot(projectRoot).resolve(ISOLATION_ENV_SCRIPT);
    }

    private static Path requireNonNull(Path path) {
        return Objects.requireNonNull(path, "路径参数不能为 null");
    }
}
