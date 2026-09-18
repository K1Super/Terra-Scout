package com.terrascout.orchestrator.app.service;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.repository.CommandExecutionRepository;
import com.terrascout.orchestrator.app.repository.TaskRepository;
import com.terrascout.orchestrator.app.repository.TaskStepRepository;
import com.terrascout.orchestrator.core.domain.CommandExecution;
import com.terrascout.orchestrator.core.domain.Task;
import com.terrascout.orchestrator.core.domain.TaskStep;
import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.enums.CommandStatusEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务执行日志：任务 / 步骤 / 命令执行三类实体的落库组件（异步编排线程独立事务写入）。
 *
 * <p>每个方法自成一个事务（编排线程不持长事务），确保任务线程自报心跳、步骤状态推进、
 * 命令执行审计（security.md 6.4）都即时可见。
 */
@Service
public class TaskJournal {

    private final TaskRepository taskRepository;
    private final TaskStepRepository stepRepository;
    private final CommandExecutionRepository commandRepository;
    private final ObjectMapper objectMapper;

    public TaskJournal(TaskRepository taskRepository,
                       TaskStepRepository stepRepository,
                       CommandExecutionRepository commandRepository,
                       ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.commandRepository = commandRepository;
        this.objectMapper = objectMapper;
    }

    /** 任务进入 RUNNING，记录自报锁属主与首跳心跳（D-007）。 */
    @Transactional
    public void markRunning(String taskId, String lockOwner) {
        Task task = requireTask(taskId);
        long now = System.currentTimeMillis();
        task.setStatus(TaskStatusEnum.RUNNING);
        task.setLockOwner(lockOwner);
        task.setHeartbeatAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);
    }

    /** 任务线程自报心跳（引擎调度线程每 1s 回调）。 */
    @Transactional
    public void heartbeat(String taskId) {
        Task task = requireTask(taskId);
        long now = System.currentTimeMillis();
        task.setHeartbeatAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);
    }

    /** 单步进入 RUNNING（重试会重置 startedAt / error）。 */
    @Transactional
    public void stepStarted(String taskId, int stepIndex, String stepName) {
        TaskStep step = findOrCreate(taskId, stepIndex);
        step.setStepName(stepName);
        step.setStatus(StepStatusEnum.RUNNING);
        step.setStartedAt(System.currentTimeMillis());
        step.setErrorCode(null);
        step.setErrorMsg(null);
        stepRepository.save(step);
    }

    /** 单步终态落库（SUCCESS / SKIPPED）。 */
    @Transactional
    public void stepFinished(String taskId, int stepIndex, StepStatusEnum status, Map<String, Object> output) {
        TaskStep step = findOrCreate(taskId, stepIndex);
        step.setStatus(status);
        step.setFinishedAt(System.currentTimeMillis());
        step.setOutputJson(json(output));
        stepRepository.save(step);
    }

    /** 单步失败落库（FAILED）。 */
    @Transactional
    public void stepFailed(String taskId, int stepIndex, int code, String msg) {
        TaskStep step = findOrCreate(taskId, stepIndex);
        step.setStatus(StepStatusEnum.FAILED);
        step.setFinishedAt(System.currentTimeMillis());
        step.setErrorCode(code);
        step.setErrorMsg(msg);
        stepRepository.save(step);
    }

    /** 单步回滚落库（ROLLED_BACK）。 */
    @Transactional
    public void stepRolledBack(String taskId, int stepIndex) {
        TaskStep step = findOrCreate(taskId, stepIndex);
        step.setStatus(StepStatusEnum.ROLLED_BACK);
        step.setFinishedAt(System.currentTimeMillis());
        stepRepository.save(step);
    }

    /** 任务终态落库。 */
    @Transactional
    public void complete(String taskId, TaskStatusEnum status, double progress,
                         Integer errorCode, String errorMsg) {
        Task task = requireTask(taskId);
        task.setStatus(status);
        task.setProgress(progress);
        task.setErrorCode(errorCode);
        task.setErrorMsg(errorMsg);
        task.setUpdatedAt(System.currentTimeMillis());
        taskRepository.save(task);
    }

    /** 命令执行审计落库（security.md 6.4：每次命令执行写记录）。 */
    @Transactional
    public void recordCommand(String projectId, CommandSpec spec, String workDir, Integer exitCode,
                              CommandStatusEnum status, long startedAt, Long finishedAt) {
        CommandExecution execution = new CommandExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setProjectId(projectId);
        execution.setCommand(spec.getCommand());
        execution.setArgsJson(json(spec.getArgs() == null ? Collections.emptyList() : spec.getArgs()));
        execution.setWorkDir(workDir);
        execution.setExitCode(exitCode);
        execution.setStatus(status);
        execution.setStartedAt(startedAt);
        execution.setFinishedAt(finishedAt);
        commandRepository.save(execution);
    }

    private TaskStep findOrCreate(String taskId, int stepIndex) {
        return stepRepository.findByTaskIdAndStepIndex(taskId, stepIndex).orElseGet(() -> {
            TaskStep step = new TaskStep();
            step.setId(UUID.randomUUID().toString());
            step.setTaskId(taskId);
            step.setStepIndex(stepIndex);
            step.setStatus(StepStatusEnum.PENDING);
            step.setRetryCount(0);
            return step;
        });
    }

    private Task requireTask(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在: " + taskId));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
