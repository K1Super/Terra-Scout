package com.terrascout.orchestrator.core.domain;

import java.time.LocalDate;

import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.CveSeverityEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * SDK 版本实体（表 sdk_version；字段与 sdk-metadata-schema.json 的 sdkEntry
 * 一一对应，camelCase → snake_case 直接映射）。
 *
 * <p>唯一键（language, version, os, arch）；lts / eol / cve_count / highest_cve_severity
 * 为版本匹配算法的决策依据。
 */
@Entity
@Table(name = "sdk_version")
public class SdkVersion {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false, length = 32)
    private LanguageEnum language;

    @Column(name = "version", nullable = false, length = 64)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "os", nullable = false, length = 32)
    private OsTypeEnum os;

    @Enumerated(EnumType.STRING)
    @Column(name = "arch", nullable = false, length = 32)
    private ArchEnum arch;

    @Column(name = "download_url", nullable = false, length = 1024)
    private String downloadUrl;

    @Column(name = "sha256", nullable = false, length = 128)
    private String sha256;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "lts", nullable = false)
    private boolean lts;

    @Column(name = "eol", nullable = false)
    private boolean eol;

    @Column(name = "eol_date")
    private LocalDate eolDate;

    @Column(name = "cve_count", nullable = false)
    private int cveCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "highest_cve_severity", nullable = false, length = 16)
    private CveSeverityEnum highestCveSeverity;

    @Column(name = "license", length = 128)
    private String license;

    @Column(name = "distribution", length = 64)
    private String distribution;

    @Column(name = "vendor", length = 128)
    private String vendor;

    @Column(name = "release_time")
    private Long releaseTime;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

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

    public OsTypeEnum getOs() {
        return os;
    }

    public void setOs(OsTypeEnum os) {
        this.os = os;
    }

    public ArchEnum getArch() {
        return arch;
    }

    public void setArch(ArchEnum arch) {
        this.arch = arch;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
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

    public LocalDate getEolDate() {
        return eolDate;
    }

    public void setEolDate(LocalDate eolDate) {
        this.eolDate = eolDate;
    }

    public int getCveCount() {
        return cveCount;
    }

    public void setCveCount(int cveCount) {
        this.cveCount = cveCount;
    }

    public CveSeverityEnum getHighestCveSeverity() {
        return highestCveSeverity;
    }

    public void setHighestCveSeverity(CveSeverityEnum highestCveSeverity) {
        this.highestCveSeverity = highestCveSeverity;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public String getDistribution() {
        return distribution;
    }

    public void setDistribution(String distribution) {
        this.distribution = distribution;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public Long getReleaseTime() {
        return releaseTime;
    }

    public void setReleaseTime(Long releaseTime) {
        this.releaseTime = releaseTime;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
