package com.terrascout.orchestrator.core.dto;

import java.util.List;

import com.terrascout.orchestrator.core.enums.LanguageEnum;

/**
 * SDK 安装计划项。
 *
 * <p>REUSE = 本机全局仓库已有满足约束的版本，直接复用（跨项目复用语义）；
 * INSTALL = 需要下载安装到 {data-root}\sdks\{language}\{version}。
 *
 * <p>{@code candidates} 为可选版本候选表（首项 = 自动推荐，N≤5），供前端选配版本。
 */
public class SdkInstallItem {

    /** 安装动作。 */
    public enum Action {
        /** 下载安装。 */
        INSTALL,
        /** 复用已装版本。 */
        REUSE
    }

    private LanguageEnum language;

    private String version;

    private Action action;

    /** 预估体积（字节）。 */
    private Long estimatedSizeBytes;

    /** 决策理由（面向用户展示）。 */
    private String reason;

    /** 可选版本候选表（首项 = 自动推荐）。 */
    private List<SdkVersionCandidate> candidates;

    public LanguageEnum getLanguage() {
        return language;
    }

    public void setLanguage(LanguageEnum language) {
        this.language = language;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public Long getEstimatedSizeBytes() {
        return estimatedSizeBytes;
    }

    public void setEstimatedSizeBytes(Long estimatedSizeBytes) {
        this.estimatedSizeBytes = estimatedSizeBytes;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<SdkVersionCandidate> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<SdkVersionCandidate> candidates) {
        this.candidates = candidates;
    }
}
