package com.terrascout.orchestrator.core.enums;

/**
 * 命令执行状态（ddl-migration.md 2.4 command_execution.status）。
 */
public enum CommandStatusEnum {
    /** 执行中。 */
    RUNNING,
    /** 成功退出（exit code 0）。 */
    SUCCESS,
    /** 失败退出（非零 exit code）。 */
    FAILED,
    /** 执行超时被终止。 */
    TIMEOUT
}
