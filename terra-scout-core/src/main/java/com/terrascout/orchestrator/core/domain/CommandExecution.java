package com.terrascout.orchestrator.core.domain;

import com.terrascout.orchestrator.core.enums.CommandStatusEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 命令执行记录实体（ddl-migration.md 2.3 表 command_execution；审计三要素之一）。
 *
 * <p>命令执行安全约束（security.md 6.4 / D-008）：List 形式参数、白名单命令、参数白名单正则、
 * 超时、审计记录（本表）。
 */
@Entity
@Table(name = "command_execution")
public class CommandExecution {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "project_id", length = 36)
    private String projectId;

    @Column(name = "command", nullable = false, columnDefinition = "TEXT")
    private String command;

    @Column(name = "args_json", nullable = false, columnDefinition = "TEXT")
    private String argsJson;

    @Column(name = "work_dir", nullable = false, length = 1024)
    private String workDir;

    @Column(name = "env_json", columnDefinition = "TEXT")
    private String envJson;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "stdout_file", length = 1024)
    private String stdoutFile;

    @Column(name = "stderr_file", length = 1024)
    private String stderrFile;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CommandStatusEnum status;

    @Column(name = "started_at", nullable = false)
    private long startedAt;

    @Column(name = "finished_at")
    private Long finishedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getArgsJson() {
        return argsJson;
    }

    public void setArgsJson(String argsJson) {
        this.argsJson = argsJson;
    }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public String getEnvJson() {
        return envJson;
    }

    public void setEnvJson(String envJson) {
        this.envJson = envJson;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getStdoutFile() {
        return stdoutFile;
    }

    public void setStdoutFile(String stdoutFile) {
        this.stdoutFile = stdoutFile;
    }

    public String getStderrFile() {
        return stderrFile;
    }

    public void setStderrFile(String stderrFile) {
        this.stderrFile = stderrFile;
    }

    public CommandStatusEnum getStatus() {
        return status;
    }

    public void setStatus(CommandStatusEnum status) {
        this.status = status;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }
}
