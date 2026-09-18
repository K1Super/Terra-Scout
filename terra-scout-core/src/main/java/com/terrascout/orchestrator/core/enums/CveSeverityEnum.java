package com.terrascout.orchestrator.core.enums;

/**
 * CVE 最高严重级别（ddl-migration.md 2.4 sdk_version.highest_cve_severity；master-plan D-017）。
 *
 * <p>版本匹配算法（version-matcher-algorithm.md）依据 {@link #severityRank()} 比较：
 * 仅当约束只能由 HIGH / CRITICAL 版本满足时拒绝并抛 422008。
 */
public enum CveSeverityEnum {
    /** 无已知漏洞。 */
    NONE,
    /** 低危。 */
    LOW,
    /** 中危。 */
    MEDIUM,
    /** 高危。 */
    HIGH,
    /** 严重。 */
    CRITICAL;

    /** 比较序：0=NONE … 4=CRITICAL；越高越危险。 */
    public int severityRank() {
        return ordinal();
    }
}
