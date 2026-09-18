package com.terrascout.orchestrator.core.enums;

/**
 * 任务步骤状态（ddl-migration.md 2.4 task_step.status）。
 */
public enum StepStatusEnum {
    /** 未开始。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 执行成功。 */
    SUCCESS,
    /** 执行失败。 */
    FAILED,
    /** 被跳过（如 REUSE 语义下的安装步骤）。 */
    SKIPPED,
    /** 已回滚。 */
    ROLLED_BACK
}
