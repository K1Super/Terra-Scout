package com.terrascout.orchestrator.app.step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.core.dto.InstallPlan;
import com.terrascout.orchestrator.core.dto.ProjectConstraint;
import com.terrascout.orchestrator.core.dto.SdkInstallItem;
import com.terrascout.orchestrator.core.dto.VersionOverride;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;

/**
 * 装配会话（跨步骤共享可变状态，非 Spring bean，随每次任务执行构造）。
 *
 * <p>TaskEngine 每步新建 {@code TaskStepContext} 且仅 setProjectId，因此跨步骤共享状态
 * （约束 / 计划 / SDK 主页 / 隔离域 / 注入环境 / 失败原因）统一收敛到本对象。
 */
public class AssemblySession {

    private String taskId;
    private String projectId;
    private Path projectRoot;
    private ProjectTypeEnum projectType;
    private List<ProjectConstraint> constraints = new ArrayList<>();
    private InstallPlan plan;
    private Map<LanguageEnum, SdkInstallItem> sdkPlan = new LinkedHashMap<>();
    private List<VersionOverride> versionOverrides;
    private Map<String, String> sdkHomes = new LinkedHashMap<>();
    private Path isolationDir;
    private Map<String, String> env = new LinkedHashMap<>();
    private Integer errorCode;
    private String errorMsg;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public void setProjectRoot(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public ProjectTypeEnum getProjectType() {
        return projectType;
    }

    public void setProjectType(ProjectTypeEnum projectType) {
        this.projectType = projectType;
    }

    public List<ProjectConstraint> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<ProjectConstraint> constraints) {
        this.constraints = constraints;
    }

    public InstallPlan getPlan() {
        return plan;
    }

    public void setPlan(InstallPlan plan) {
        this.plan = plan;
    }

    public Map<LanguageEnum, SdkInstallItem> getSdkPlan() {
        return sdkPlan;
    }

    public void setSdkPlan(Map<LanguageEnum, SdkInstallItem> sdkPlan) {
        this.sdkPlan = sdkPlan;
    }

    /** SDK 版本选配覆盖（可能为 null 表示全部自动推荐）。 */
    public List<VersionOverride> getVersionOverrides() {
        return versionOverrides;
    }

    public void setVersionOverrides(List<VersionOverride> versionOverrides) {
        this.versionOverrides = versionOverrides;
    }

    public Map<String, String> getSdkHomes() {
        return sdkHomes;
    }

    public void setSdkHomes(Map<String, String> sdkHomes) {
        this.sdkHomes = sdkHomes;
    }

    public Path getIsolationDir() {
        return isolationDir;
    }

    public void setIsolationDir(Path isolationDir) {
        this.isolationDir = isolationDir;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    /** 记录步骤失败原因（编排结束后写回 task.error_code / error_msg）。 */
    public void fail(int code, String msg) {
        this.errorCode = code;
        this.errorMsg = msg;
    }
}
