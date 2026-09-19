package com.terrascout.orchestrator.app.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.repository.ProjectRepository;
import com.terrascout.orchestrator.app.repository.TaskRepository;
import com.terrascout.orchestrator.app.repository.TaskStepRepository;
import com.terrascout.orchestrator.core.domain.Project;
import com.terrascout.orchestrator.core.domain.Task;
import com.terrascout.orchestrator.core.domain.TaskStep;
import com.terrascout.orchestrator.core.dto.ExecuteRequest;
import com.terrascout.orchestrator.core.dto.ExecuteResponse;
import com.terrascout.orchestrator.core.dto.TaskResponse;
import com.terrascout.orchestrator.core.dto.VersionOverride;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;
import com.terrascout.orchestrator.core.enums.TaskTypeEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 任务服务：执行装配 / 列表 / 详情 / 操作。
 *
 * <p>异步语义由 taskId + 轮询表达：触发返回 HTTP 200 + QUEUED；planId 即 projectId
 * （一个项目一个装配计划，计划不单独持久化），不存在 → 404003；幂等重放返回 200 + 首次结果。
 * 任务在事务提交后（afterCommit）提交到 taskExecutor 异步编排。
 */
@Service
public class TaskService {

    /** 任务类型子串到枚举映射的默认类型。 */
    private static final TaskTypeEnum DEFAULT_TYPE = TaskTypeEnum.PROJECT_ASSEMBLE;

    private final TaskRepository repository;
    private final TaskStepRepository stepRepository;
    private final AuditService auditService;
    private final ProjectRepository projectRepository;
    private final TaskAssemblyOrchestrator orchestrator;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    public TaskService(TaskRepository repository, TaskStepRepository stepRepository,
                       AuditService auditService, ProjectRepository projectRepository,
                       TaskAssemblyOrchestrator orchestrator,
                       @Qualifier("taskExecutor") ThreadPoolTaskExecutor taskExecutor,
                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.stepRepository = stepRepository;
        this.auditService = auditService;
        this.projectRepository = projectRepository;
        this.orchestrator = orchestrator;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    /** 触发装配任务：confirm 校验 → planId(projectId) 解析 → 幂等去重 → 创建 QUEUED → afterCommit 编排。 */
    @Transactional
    public ExecuteResponse execute(ExecuteRequest request) {
        if (!request.isConfirm()) {
            throw new TerraScoutException(TerraScoutError.FIELD_MISSING,
                    "confirm 必须为 true（危险操作二次确认）");
        }
        String planId = request.getPlanId();
        if (planId == null || planId.isBlank()) {
            throw new TerraScoutException(TerraScoutError.FIELD_MISSING, "planId 不能为空");
        }
        Project project = projectRepository.findById(planId)
                .orElseThrow(() -> new TerraScoutException(TerraScoutError.PLAN_NOT_FOUND,
                        "装配计划不存在: " + planId));

        String idem = request.getIdempotencyKey();
        if (idem != null && !idem.isBlank()) {
            Task existing = repository.findByIdempotencyKey(idem).orElse(null);
            if (existing != null) {
                return toExecuteResponse(existing);
            }
        }

        Task task = new Task();
        task.setId(UUID.randomUUID().toString());
        task.setTaskType(DEFAULT_TYPE);
        task.setStatus(TaskStatusEnum.QUEUED);
        task.setPayloadJson(payload(planId, request.getVersionOverrides()));
        task.setProgress(0);
        task.setMaxRetry(3);
        task.setIdempotencyKey(idem);
        long now = System.currentTimeMillis();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        repository.save(task);

        auditService.record(task.getId(), "TASK_CREATE", "task", task.getId(),
                null, task.getPayloadJson(), "SUCCESS");

        String taskId = task.getId();
        String projectId = project.getId();
        List<VersionOverride> overrides = request.getVersionOverrides();
        afterCommit(() -> orchestrator.run(taskId, projectId, overrides));
        return toExecuteResponse(task);
    }

    /** 任务列表（可按 status 过滤）。 */
    @Transactional(readOnly = true)
    public Page<Task> list(TaskStatusEnum status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        PageRequest pr = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return status == null ? repository.findAll(pr) : repository.findByStatus(status, pr);
    }

    /** 任务详情，不存在 → 404002。 */
    @Transactional(readOnly = true)
    public TaskResponse detail(String taskId) {
        Task task = repository.findById(taskId)
                .orElseThrow(() -> new TerraScoutException(TerraScoutError.TASK_NOT_FOUND));
        return toTaskResponse(task);
    }

    /** 任务步骤日志，不存在 → 404002。 */
    @Transactional(readOnly = true)
    public Map<String, Object> logs(String taskId) {
        if (!repository.existsById(taskId)) {
            throw new TerraScoutException(TerraScoutError.TASK_NOT_FOUND);
        }
        List<TaskStep> steps = stepRepository.findByTaskIdOrderByStepIndex(taskId);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (TaskStep step : steps) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("stepIndex", step.getStepIndex());
            line.put("stepName", step.getStepName());
            line.put("status", step.getStatus().name());
            line.put("errorCode", step.getErrorCode());
            line.put("errorMsg", step.getErrorMsg());
            line.put("startedAt", step.getStartedAt());
            line.put("finishedAt", step.getFinishedAt());
            lines.add(line);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("lines", lines);
        result.put("truncated", false);
        return result;
    }

    private void afterCommit(Runnable callback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskExecutor.submit(callback);
            }
        });
    }

    private String payload(String planId, List<VersionOverride> overrides) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", planId);
        if (overrides != null && !overrides.isEmpty()) {
            map.put("versionOverrides", overrides);
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{\"planId\":\"" + planId + "\"}";
        }
    }

    private static ExecuteResponse toExecuteResponse(Task task) {
        ExecuteResponse response = new ExecuteResponse();
        response.setTaskId(task.getId());
        response.setStatus(task.getStatus());
        return response;
    }

    private TaskResponse toTaskResponse(Task task) {
        TaskResponse response = new TaskResponse();
        response.setTaskId(task.getId());
        response.setType(task.getTaskType().name());
        response.setStatus(task.getStatus());
        response.setProgress(task.getProgress());
        response.setErrorCode(task.getErrorCode());
        response.setErrorMsg(task.getErrorMsg());
        response.setRetryCount(task.getRetryCount());
        response.setMaxRetry(task.getMaxRetry());
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        return response;
    }
}
