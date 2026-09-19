package com.terrascout.orchestrator.task;

import java.util.List;
import java.util.Objects;

/**
 * 任务步骤的静态定义（PROJECT_ASSEMBLE 步骤表）。
 *
 * @param name            步骤名
 * @param timeoutSeconds  允许的最长执行时间（秒）
 * @param pausable        是否可在执行中暂停
 * @param retryable       失败后是否可重试
 * @param hasRollback     是否有补偿（回滚）操作
 */
public record TaskStepDefinition(
        String name,
        int timeoutSeconds,
        boolean pausable,
        boolean retryable,
        boolean hasRollback) {

    /** 步骤名常量（扩展四语言）。 */
    public static final String DETECT_PROJECT = "DETECT_PROJECT";
    public static final String PARSE_MANIFEST = "PARSE_MANIFEST";
    public static final String MATCH_VERSION = "MATCH_VERSION";
    public static final String INSTALL_SDK_JAVA = "INSTALL_SDK_JAVA";
    public static final String INSTALL_SDK_NODE = "INSTALL_SDK_NODE";
    public static final String INSTALL_SDK_GO = "INSTALL_SDK_GO";
    public static final String INSTALL_SDK_PYTHON = "INSTALL_SDK_PYTHON";
    public static final String CREATE_ISOLATION = "CREATE_ISOLATION";
    public static final String INSTALL_DEPENDENCIES = "INSTALL_DEPENDENCIES";
    public static final String BIND_ENV = "BIND_ENV";
    public static final String VERIFY_PROJECT = "VERIFY_PROJECT";

    /** SDK 安装步骤超时 30 分钟。 */
    public static final int SDK_INSTALL_TIMEOUT_SECONDS = 30 * 60;
    /** 验证步骤超时 10 分钟。 */
    public static final int VERIFY_TIMEOUT_SECONDS = 10 * 60;

    /**
     * PROJECT_ASSEMBLE 任务的完整步骤序列（四语言共 11 步）。
     */
    public static List<TaskStepDefinition> projectAssemble() {
        return List.of(
                step(DETECT_PROJECT, 10, false, true, false),
                step(PARSE_MANIFEST, 30, false, true, false),
                step(MATCH_VERSION, 10, false, true, false),
                step(INSTALL_SDK_JAVA, SDK_INSTALL_TIMEOUT_SECONDS, true, true, true),
                step(INSTALL_SDK_NODE, SDK_INSTALL_TIMEOUT_SECONDS, true, true, true),
                step(INSTALL_SDK_GO, SDK_INSTALL_TIMEOUT_SECONDS, true, true, true),
                step(INSTALL_SDK_PYTHON, SDK_INSTALL_TIMEOUT_SECONDS, true, true, true),
                step(CREATE_ISOLATION, 10, false, true, true),
                step(INSTALL_DEPENDENCIES, SDK_INSTALL_TIMEOUT_SECONDS, true, true, true),
                step(BIND_ENV, 10, false, true, true),
                step(VERIFY_PROJECT, VERIFY_TIMEOUT_SECONDS, false, true, false));
    }

    private static TaskStepDefinition step(String name, int timeout, boolean pausable,
                                           boolean retryable, boolean hasRollback) {
        return new TaskStepDefinition(name, timeout, pausable, retryable, hasRollback);
    }

    /** 紧凑记录规范构造（杜绝裸 partial）。 */
    public TaskStepDefinition {
        Objects.requireNonNull(name, "name 不能为 null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("step name 不能为空");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须为正: " + name);
        }
    }
}
