package com.terrascout.orchestrator.core.dto;

/**
 * SDK 版本候选（openapi SdkVersionCandidate；裁决 R48）。
 *
 * <p>装配详情/预览计划中每个 SDK 安装项附带的候选版本表（首项 = 自动推荐），供前端以选择器
 * 让用户自行选配版本，execute 阶段把所选版本经 {@code versionOverrides} 回传覆盖。
 */
public class SdkVersionCandidate {

    /** 候选版本号。 */
    private String version;

    /** 是否 LTS。 */
    private boolean lts;

    /** 是否已 EOL（候选表仅收录非 EOL 版本，保留字段供 UI 标注语义）。 */
    private boolean eol;

    /** 预估体积（字节）。 */
    private Long sizeBytes;

    /** 本机是否已成功安装该版本（可 REUSE）。 */
    private boolean installed;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public boolean isLts() {
        return lts;
    }

    public void setLts(boolean lts) {
        this.lts = lts;
    }

    public boolean isEol() {
        return eol;
    }

    public void setEol(boolean eol) {
        this.eol = eol;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public boolean isInstalled() {
        return installed;
    }

    public void setInstalled(boolean installed) {
        this.installed = installed;
    }
}
