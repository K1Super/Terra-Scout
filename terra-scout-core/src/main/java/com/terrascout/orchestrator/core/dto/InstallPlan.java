package com.terrascout.orchestrator.core.dto;

import java.util.List;

/**
 * 装配计划（依据约束与已装记录生成）。
 *
 * <p>404003：执行时 planId 不存在即返回该错误码。
 */
public class InstallPlan {

    /** 计划 ID（UUID v4）。 */
    private String planId;

    /** SDK 安装清单（INSTALL / REUSE）。 */
    private List<SdkInstallItem> sdkInstalls;

    /** 依赖隔离安装清单。 */
    private List<DependencyInstallItem> dependencyInstalls;

    /** 装配后验证命令。 */
    private List<CommandSpec> verifyCommands;

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public List<SdkInstallItem> getSdkInstalls() {
        return sdkInstalls;
    }

    public void setSdkInstalls(List<SdkInstallItem> sdkInstalls) {
        this.sdkInstalls = sdkInstalls;
    }

    public List<DependencyInstallItem> getDependencyInstalls() {
        return dependencyInstalls;
    }

    public void setDependencyInstalls(List<DependencyInstallItem> dependencyInstalls) {
        this.dependencyInstalls = dependencyInstalls;
    }

    public List<CommandSpec> getVerifyCommands() {
        return verifyCommands;
    }

    public void setVerifyCommands(List<CommandSpec> verifyCommands) {
        this.verifyCommands = verifyCommands;
    }
}
