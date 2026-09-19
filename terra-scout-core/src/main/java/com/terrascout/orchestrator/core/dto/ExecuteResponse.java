package com.terrascout.orchestrator.core.dto;

import com.terrascout.orchestrator.core.enums.TaskStatusEnum;

/**
 * 执行装配响应。
 *
 * <p>异步语义：HTTP 200 + 200000，由 taskId + 轮询表达进度，不使用 202。
 */
public class ExecuteResponse {

    /** 任务 ID（UUID v4）。 */
    private String taskId;

    /** 初始状态（PENDING / QUEUED）。 */
    private TaskStatusEnum status;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public TaskStatusEnum getStatus() {
        return status;
    }

    public void setStatus(TaskStatusEnum status) {
        this.status = status;
    }
}
