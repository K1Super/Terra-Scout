package com.terrascout.orchestrator.core.domain;

import com.terrascout.orchestrator.core.enums.StepStatusEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 任务步骤实体（ddl-migration.md 2.3 表 task_step）。
 *
 * <p>(task_id, step_index) 唯一；rollback_json 记录回滚所需上下文（如前向操作产生的目录），
 * 由 state-machine.md 回滚链消费。
 */
@Entity
@Table(name = "task_step")
public class TaskStep {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "step_name", nullable = false, length = 128)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StepStatusEnum status;

    @Column(name = "input_json", columnDefinition = "TEXT")
    private String inputJson;

    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    @Column(name = "rollback_json", columnDefinition = "TEXT")
    private String rollbackJson;

    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "started_at")
    private Long startedAt;

    @Column(name = "finished_at")
    private Long finishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public StepStatusEnum getStatus() {
        return status;
    }

    public void setStatus(StepStatusEnum status) {
        this.status = status;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(String outputJson) {
        this.outputJson = outputJson;
    }

    public String getRollbackJson() {
        return rollbackJson;
    }

    public void setRollbackJson(String rollbackJson) {
        this.rollbackJson = rollbackJson;
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

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
