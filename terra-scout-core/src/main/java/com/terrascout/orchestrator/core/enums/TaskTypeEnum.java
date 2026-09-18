package com.terrascout.orchestrator.core.enums;

/**
 * 任务类型（ddl-migration.md 2.4 task.task_type）。
 */
public enum TaskTypeEnum {
    /** 项目装配：SDK 安装 + 依赖隔离安装 + 验证。 */
    PROJECT_ASSEMBLE,
    /** 单独安装 SDK。 */
    SDK_INSTALL,
    /** 卸载 SDK。 */
    SDK_UNINSTALL,
    /** 隔离域依赖安装。 */
    DEPENDENCY_INSTALL,
    /** 项目环境验证。 */
    VERIFY_PROJECT
}
