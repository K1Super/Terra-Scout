package com.terrascout.orchestrator.core.domain;

import com.terrascout.orchestrator.core.enums.InstallStatusEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.ScopeEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * SDK 安装记录实体（表 sdk_install_record）。
 *
 * <p>install_path 指向全局仓库物理位置（{data-root}\sdks\{language}\{version}）；
 * scope 表达记录归属（PROJECT = 由项目装配触发创建），不表达物理位置。
 */
@Entity
@Table(name = "sdk_install_record")
public class SdkInstallRecord {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 32)
    private LanguageEnum language;

    @Column(name = "version", nullable = false, length = 64)
    private String version;

    @Column(name = "install_path", nullable = false, length = 1024)
    private String installPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private ScopeEnum scope;

    @Column(name = "project_id", length = 36)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InstallStatusEnum status;

    @Column(name = "checksum", length = 128)
    private String checksum;

    @Column(name = "installed_at", nullable = false)
    private long installedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

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

    public String getInstallPath() {
        return installPath;
    }

    public void setInstallPath(String installPath) {
        this.installPath = installPath;
    }

    public ScopeEnum getScope() {
        return scope;
    }

    public void setScope(ScopeEnum scope) {
        this.scope = scope;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public InstallStatusEnum getStatus() {
        return status;
    }

    public void setStatus(InstallStatusEnum status) {
        this.status = status;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public long getInstalledAt() {
        return installedAt;
    }

    public void setInstalledAt(long installedAt) {
        this.installedAt = installedAt;
    }
}
