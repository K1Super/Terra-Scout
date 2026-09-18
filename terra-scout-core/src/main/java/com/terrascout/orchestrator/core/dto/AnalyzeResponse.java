package com.terrascout.orchestrator.core.dto;

import java.util.List;

import com.terrascout.orchestrator.core.enums.OsTypeEnum;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;

/**
 * 项目导入分析响应（rest-schema.md 3.4.1；openapi AnalyzeResponse）。
 */
public class AnalyzeResponse {

    /** 项目 ID（UUID v4）。 */
    private String projectId;

    /** 项目名（目录名）。 */
    private String name;

    /** 项目类型。 */
    private ProjectTypeEnum type;

    /** 当前操作系统。 */
    private OsTypeEnum osType;

    /** 项目声明的版本约束集合。 */
    private List<ProjectConstraint> constraints;

    /** 装配计划（若约束可解析）。 */
    private InstallPlan plan;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProjectTypeEnum getType() {
        return type;
    }

    public void setType(ProjectTypeEnum type) {
        this.type = type;
    }

    public OsTypeEnum getOsType() {
        return osType;
    }

    public void setOsType(OsTypeEnum osType) {
        this.osType = osType;
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
}
