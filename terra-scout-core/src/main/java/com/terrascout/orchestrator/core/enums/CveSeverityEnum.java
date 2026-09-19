package com.terrascout.orchestrator.core.enums;

/**
 * CVE 最高严重级别（对应 sdk_version.highest_cve_severity）。
 *
 * <p>版本匹配算法依据 {@link #severityRank()} 比较：
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
