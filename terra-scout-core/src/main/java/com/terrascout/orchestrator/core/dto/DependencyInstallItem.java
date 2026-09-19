package com.terrascout.orchestrator.core.dto;

/**
 * 依赖隔离安装计划项。
 *
 * <p>依赖按项目隔离：Maven → {projectRoot}\.devenv\m2；npm → .devenv\npm-cache。
 */
public class DependencyInstallItem {

    /** 生态标识（取值：{@code maven} / {@code npm} / {@code go} / {@code python}，小写字符串）。 */
    public static final String ECOSYSTEM_MAVEN = "maven";
    /** 生态标识：npm。 */
    public static final String ECOSYSTEM_NPM = "npm";
    /** 生态标识：Go Modules。 */
    public static final String ECOSYSTEM_GO = "go";
    /** 生态标识：pip（requirements.txt）。 */
    public static final String ECOSYSTEM_PYTHON = "python";

    /** 生态（maven / npm / go / python）。 */
    private String ecosystem;

    /** 隔离目录相对路径（如 .devenv/m2）。 */
    private String isolation;

    /** 安装命令。 */
    private CommandSpec installCommand;

    public String getEcosystem() {
        return ecosystem;
    }

    public void setEcosystem(String ecosystem) {
        this.ecosystem = ecosystem;
    }

    public String getIsolation() {
        return isolation;
    }

    public void setIsolation(String isolation) {
        this.isolation = isolation;
    }

    public CommandSpec getInstallCommand() {
        return installCommand;
    }

    public void setInstallCommand(CommandSpec installCommand) {
        this.installCommand = installCommand;
    }
}
