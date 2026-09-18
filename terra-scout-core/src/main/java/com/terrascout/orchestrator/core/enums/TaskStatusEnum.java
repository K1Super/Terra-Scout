package com.terrascout.orchestrator.core.enums;

/**
 * 任务状态（ddl-migration.md 2.4 task.status；state-machine.md 9 状态 / 10 事件转移表）。
 *
 * <p>终态集合（409005）：SUCCESS / FAILED / CANCELLED / ROLLED_BACK——终态任务拒绝再次操作。
 */
public enum TaskStatusEnum {
    /** 已创建，未入队。 */
    PENDING,
    /** 已入队，等待线程池调度。 */
    QUEUED,
    /** 执行中（任务线程自报心跳，D-007）。 */
    RUNNING,
    /** 用户暂停。 */
    PAUSED,
    /** 执行成功（终态）。 */
    SUCCESS,
    /** 执行失败（终态）。 */
    FAILED,
    /** 用户取消（终态）。 */
    CANCELLED,
    /** 回滚中（失败补偿）。 */
    ROLLING_BACK,
    /** 已回滚（终态）。 */
    ROLLED_BACK;

    /** 是否终态（409005 语义：终态任务无法再次操作）。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED || this == ROLLED_BACK;
    }
}
