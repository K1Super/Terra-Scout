package com.terrascout.orchestrator.core.dto;

import java.util.List;

/**
 * 执行装配请求。
 *
 * <p>confirm 仅接受 true（危险操作二次确认）；idempotencyKey 重复提交返回 200 + 首次结果。
 * versionOverrides 为可选 SDK 版本选配，每条覆盖对应语言的自动推荐版本。
 */
public class ExecuteRequest {

    /** 装配计划 ID（UUID v4，必填）。 */
    private String planId;

    /** 危险操作确认，仅接受 true。 */
    private boolean confirm;

    /** 幂等键（UUID v4，可选）。 */
    private String idempotencyKey;

    /** SDK 版本选配覆盖（可选）。 */
    private List<VersionOverride> versionOverrides;

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public boolean isConfirm() {
        return confirm;
    }

    public void setConfirm(boolean confirm) {
        this.confirm = confirm;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public List<VersionOverride> getVersionOverrides() {
        return versionOverrides;
    }

    public void setVersionOverrides(List<VersionOverride> versionOverrides) {
        this.versionOverrides = versionOverrides;
    }
}
