package com.terrascout.orchestrator.core.dto;

import java.util.List;

import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;

/**
 * 任务详情响应（rest-schema.md 3.4.7；openapi TaskDetail）。
 */
public class TaskResponse {

    /** 任务 ID。 */
    private String taskId;

    /** 任务类型（task_type 字符串值）。 */
    private String type;

    /** 任务状态。 */
    private TaskStatusEnum status;

    /** 进度 0-1。 */
    private double progress;

    /** 步骤详情（按 step_index 排序）。 */
    private List<TaskStepDetail> steps;

    /** 失败错误码（成功为 null）。 */
    private Integer errorCode;

    /** 失败消息（成功为 null）。 */
    private String errorMsg;

    private int retryCount;

    private int maxRetry;

    private long createdAt;

    private long updatedAt;

    /**
     * 任务步骤详情（openapi TaskStepDetail）。
     */
    public static class TaskStepDetail {

        /** 步骤序号（step_index）。 */
        private int index;

        /** 步骤名。 */
        private String name;

        private StepStatusEnum status;

        private Long startedAt;

        private Long finishedAt;

        private Integer errorCode;

        private String errorMsg;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public StepStatusEnum getStatus() {
            return status;
        }

        public void setStatus(StepStatusEnum status) {
            this.status = status;
        }

        public Long getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(Long startedAt) {
            this.startedAt = startedAt;
        }

        public Long getFinishedAt() {
            return finishedAt;
        }

        public void setFinishedAt(Long finishedAt) {
            this.finishedAt = finishedAt;
        }

        public Integer getErrorCode() {
            return errorCode;
        }

        public void setErrorCode(Integer errorCode) {
            this.errorCode = errorCode;
        }

        public String getErrorMsg() {
            return errorMsg;
        }

        public void setErrorMsg(String errorMsg) {
            this.errorMsg = errorMsg;
        }
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public TaskStatusEnum getStatus() {
        return status;
    }

    public void setStatus(TaskStatusEnum status) {
        this.status = status;
    }

    public double getProgress() {
        return progress;
    }

    public void setProgress(double progress) {
        this.progress = progress;
    }

    public List<TaskStepDetail> getSteps() {
        return steps;
    }

    public void setSteps(List<TaskStepDetail> steps) {
        this.steps = steps;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
