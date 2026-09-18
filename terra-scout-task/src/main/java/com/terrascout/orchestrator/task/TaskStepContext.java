package com.terrascout.orchestrator.task;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务步骤执行上下文（task-step-definition.md 5.5）。
 *
 * <p>承载单步执行的输入/输出/回滚数据，由 {@link TaskEngine} 在每步执行前创建，
 * 完成后保留以供回滚复用。
 */
public final class TaskStepContext {

    private String taskId;
    private String projectId;
    private Path projectRoot;
    private Path isolationDir;
    private final Map<String, Object> input = new LinkedHashMap<>();
    private final Map<String, Object> output = new LinkedHashMap<>();
    private final Map<String, Object> rollbackData = new LinkedHashMap<>();

    public String getTaskId() {
        return taskId;
    }

    public TaskStepContext setTaskId(String taskId) {
        this.taskId = taskId;
        return this;
    }

    public String getProjectId() {
        return projectId;
    }

    public TaskStepContext setProjectId(String projectId) {
        this.projectId = projectId;
        return this;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public TaskStepContext setProjectRoot(Path projectRoot) {
        this.projectRoot = projectRoot;
        return this;
    }

    public Path getIsolationDir() {
        return isolationDir;
    }

    public TaskStepContext setIsolationDir(Path isolationDir) {
        this.isolationDir = isolationDir;
        return this;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public Map<String, Object> getRollbackData() {
        return rollbackData;
    }
}
