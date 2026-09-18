package com.terrascout.orchestrator.task;

import com.terrascout.orchestrator.core.enums.StepStatusEnum;

/**
 * 任务步骤执行器统一接口（task-step-definition.md 5.4 / state-machine.md 4.10）。
 *
 * <p>具体步骤实现（下载解压 / 命令执行 / 环境注入）由高级模块（download / env）提供，
 * 本模块仅定义契约并负责编排（重试 / 回滚 / 暂停判定）。
 */
public interface TaskStepExecutor {

    /**
     * 执行步骤前向操作。
     *
     * @param context 步骤上下文（含输入/输出）
     * @return 步骤结果状态：SUCCESS / SKIPPED / FAILED（PENDING / RUNNING 由引擎持有）
     */
    StepStatusEnum execute(TaskStepContext context);

    /**
     * 补偿（回滚）已完成的步骤，逆序调用。
     */
    void rollback(TaskStepContext context);

    /**
     * 是否可在执行中暂停（决定 RUNNING --PAUSE--> PAUSED 是否允许）。
     */
    boolean isPausable();

    /**
     * 是否可重试（失败后引擎据此决定是否 RETRY 而非直接终态）。
     */
    boolean isRetryable();

    /**
     * 步骤允许的最长执行时间（秒）。
     */
    int getTimeoutSeconds();
}
