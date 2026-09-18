package com.terrascout.orchestrator.app.service.catalog;

import java.time.LocalDate;

import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.CveSeverityEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

/**
 * SDK 目录条目（官方源拉取与本地元数据文件解析的中间载体，字段语义与
 * {@code sdk_version} 表一一对应，D-017）。
 *
 * <p>{@code downloadUrl} 对应 schema 文件中的 {@code url}，{@code releaseTime}
 * 对应 {@code releaseDate}（日期字符串解析为 UTC 零点 epoch 毫秒）。
 */
public class SdkCatalogEntry {

    private LanguageEnum language;
    private String version;
    private OsTypeEnum os;
    private ArchEnum arch;
    private String downloadUrl;
    private String sha256;
    private Long sizeBytes;
    private boolean lts;
    private boolean eol;
    private LocalDate eolDate;
    private int cveCount;
    private CveSeverityEnum highestCveSeverity = CveSeverityEnum.NONE;
    private String license;
    private String distribution;
    private String vendor;
    private Long releaseTime;

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
}
