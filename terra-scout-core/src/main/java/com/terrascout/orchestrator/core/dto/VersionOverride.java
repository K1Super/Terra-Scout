package com.terrascout.orchestrator.core.dto;

import com.terrascout.orchestrator.core.enums.LanguageEnum;

/**
 * SDK 版本覆盖。
 *
 * <p>执行装配时用户对某语言 SDK 的显式选配：{@code language + version} 覆盖自动推荐版本；
 * MATCH_VERSION 步骤校验其必须属于候选表（硬过滤 EOL/高危 CVE 依旧生效），否则 422006。
 */
public class VersionOverride {

    /** 目标语言。 */
    private LanguageEnum language;

    /** 用户所选版本（必须为候选表内版本）。 */
    private String version;

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
}
