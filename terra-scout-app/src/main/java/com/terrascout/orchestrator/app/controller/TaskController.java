package com.terrascout.orchestrator.app.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.terrascout.orchestrator.app.controller.ResponseModels.ApiList;
import com.terrascout.orchestrator.app.repository.TaskStepRepository;
import com.terrascout.orchestrator.app.service.TaskService;
import com.terrascout.orchestrator.core.domain.Task;
import com.terrascout.orchestrator.core.domain.TaskStep;
import com.terrascout.orchestrator.core.dto.ApiResponse;
import com.terrascout.orchestrator.core.dto.ExecuteRequest;
import com.terrascout.orchestrator.core.dto.ExecuteResponse;
import com.terrascout.orchestrator.core.dto.TaskResponse;
import com.terrascout.orchestrator.core.dto.TaskResponse.TaskStepDetail;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;
import com.terrascout.orchestrator.core.enums.TaskTypeEnum;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 任务端点（rest-schema.md 3.4.5-3.4.9）：执行装配 / 列表 / 详情 / 操作。
 */
@RestController
@RequestMapping("/api/v1/task")
public class TaskController {

    private final TaskService taskService;
    private final TaskStepRepository taskStepRepository;

    public TaskController(TaskService taskService, TaskStepRepository taskStepRepository) {
        this.taskService = taskService;
        this.taskStepRepository = taskStepRepository;
    }

    /** 执行装配（3.4.5，异步阈值：200 + QUEUED + taskId）。 */
    @PostMapping("/execute")
    public ApiResponse<ExecuteResponse> execute(@RequestBody ExecuteRequest request) {
        return ApiResponse.ok(taskService.execute(request));
    }

    /** 任务列表（3.4.6，可浇 status）。 */
    @GetMapping
    public ApiResponse<ApiList> list(
            @RequestParam(required = false) TaskStatusEnum status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Task> paged = taskService.list(status, page, size);
        return ApiResponse.ok(ResponseModels.list(paged.getContent().stream()
                .map(this::toListItem).collect(Collectors.toList()),
                paged.getTotalElements(), paged.getNumber() + 1, paged.getSize()));
    }

    /** 任务详情（3.4.7，含步骤）。 */
    @GetMapping("/{taskId}")
    public ApiResponse<TaskResponse> detail(@PathVariable String taskId) {
        TaskResponse detail = taskService.detail(taskId);
        List<TaskStep> steps = taskStepRepository.findAll().stream()
                .filter(s -> s.getTaskId().equals(taskId))
                .sorted((a, b) -> Integer.compare(a.getStepIndex(), b.getStepIndex()))
                .collect(Collectors.toList());
        detail.setSteps(steps.stream().map(this::toStepDetail).collect(Collectors.toList()));
        return ApiResponse.ok(detail);
    }

    /** 任务步骤日志（3.4.8）。 */
    @GetMapping("/{taskId}/logs")
    public ApiResponse<Map<String, Object>> logs(@PathVariable String taskId) {
        return ApiResponse.ok(taskService.logs(taskId));
    }

    /** 任务操作（3.4.9：pause/resume/cancel）。P0 返回任务当前状态（执行引擎由 task 模块驱动）。 */
    @PostMapping("/{action}")
    public ApiResponse<TaskResponse> operation(@PathVariable String action,
                                               @RequestBody TaskOperationRequest body) {
        TaskResponse detail = taskService.detail(body.getTaskId());
        return ApiResponse.ok(detail);
    }

    private Map<String, Object> toListItem(Task task) {
        Map<String, Object> item = new HashMap<>();
        item.put("taskId", task.getId());
        item.put("type", task.getTaskType() == null ? TaskTypeEnum.PROJECT_ASSEMBLE.name()
                : task.getTaskType().name());
        item.put("status", task.getStatus());
        item.put("progress", task.getProgress());
        item.put("errorCode", task.getErrorCode());
        item.put("errorMsg", task.getErrorMsg());
        item.put("createdAt", task.getCreatedAt());
        item.put("updatedAt", task.getUpdatedAt());
        return item;
    }

    private TaskStepDetail toStepDetail(TaskStep step) {
        TaskStepDetail detail = new TaskStepDetail();
        detail.setIndex(step.getStepIndex());
        detail.setName(step.getStepName());
        detail.setStatus(step.getStatus());
        detail.setStartedAt(step.getStartedAt());
        detail.setFinishedAt(step.getFinishedAt());
        detail.setErrorCode(step.getErrorCode());
        detail.setErrorMsg(step.getErrorMsg());
        return detail;
    }

    /** 任务操作请求体（3.4.9：{@code { "taskId": "uuid" }}）。 */
    public static class TaskOperationRequest {
        private String taskId;

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }
    }
}
