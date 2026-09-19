package com.terrascout.orchestrator.core.enums;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 枚举三表同步断言（三处枚举定义须保持一致）。
 *
 * <p>任何一方漂移（任一来源的枚举定义被改动）在此立即暴露。
 */
class EnumsSyncTest {

    private static List<String> names(Enum<?>[] values) {
        return Stream.of(values).map(Enum::name).collect(Collectors.toList());
    }

    @Test
    @DisplayName("LanguageEnum = sdk_version.language")
    void languageEnumMatchesDdl() {
        assertThat(names(LanguageEnum.values())).containsExactly("JAVA", "NODE", "PYTHON", "GO");
    }

    @Test
    @DisplayName("OsTypeEnum = project.os_type / sdk_version.os")
    void osTypeEnumMatchesDdl() {
        assertThat(names(OsTypeEnum.values())).containsExactly("WINDOWS", "MACOS", "LINUX");
    }

    @Test
    @DisplayName("ArchEnum = sdk_version.arch")
    void archEnumMatchesDdl() {
        assertThat(names(ArchEnum.values())).containsExactly("AMD64", "ARM64");
    }

    @Test
    @DisplayName("TaskStatusEnum = task.status（9 状态）")
    void taskStatusEnumMatchesDdl() {
        assertThat(names(TaskStatusEnum.values())).containsExactly(
                "PENDING", "QUEUED", "RUNNING", "PAUSED",
                "SUCCESS", "FAILED", "CANCELLED", "ROLLING_BACK", "ROLLED_BACK");
    }

    @Test
    @DisplayName("TaskTypeEnum = task.task_type")
    void taskTypeEnumMatchesDdl() {
        assertThat(names(TaskTypeEnum.values())).containsExactly(
                "PROJECT_ASSEMBLE", "SDK_INSTALL", "SDK_UNINSTALL", "DEPENDENCY_INSTALL", "VERIFY_PROJECT");
    }

    @Test
    @DisplayName("StepStatusEnum = task_step.status")
    void stepStatusEnumMatchesDdl() {
        assertThat(names(StepStatusEnum.values())).containsExactly(
                "PENDING", "RUNNING", "SUCCESS", "FAILED", "SKIPPED", "ROLLED_BACK");
    }

    @Test
    @DisplayName("ScopeEnum = sdk_install_record.scope")
    void scopeEnumMatchesDdl() {
        assertThat(names(ScopeEnum.values())).containsExactly("GLOBAL", "PROJECT");
    }

    @Test
    @DisplayName("InstallStatusEnum = sdk_install_record.status")
    void installStatusEnumMatchesDdl() {
        assertThat(names(InstallStatusEnum.values())).containsExactly(
                "INSTALLING", "SUCCESS", "FAILED", "ROLLED_BACK");
    }

    @Test
    @DisplayName("CommandStatusEnum = command_execution.status")
    void commandStatusEnumMatchesDdl() {
        assertThat(names(CommandStatusEnum.values())).containsExactly(
                "RUNNING", "SUCCESS", "FAILED", "TIMEOUT");
    }

    @Test
    @DisplayName("CveSeverityEnum = sdk_version.highest_cve_severity")
    void cveSeverityEnumMatchesDdl() {
        assertThat(names(CveSeverityEnum.values())).containsExactly(
                "NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    }

    @Test
    @DisplayName("ProjectTypeEnum = AnalyzeResponse.type")
    void projectTypeEnumMatchesOpenapi() {
        assertThat(names(ProjectTypeEnum.values()))
                .containsExactly("MAVEN", "NPM", "GO", "PYTHON", "MIXED", "UNKNOWN");
    }

    @Test
    @DisplayName("终态集合 = 409005 语义：SUCCESS / FAILED / CANCELLED / ROLLED_BACK")
    void taskTerminalStates() {
        for (TaskStatusEnum status : TaskStatusEnum.values()) {
            boolean expected = status == TaskStatusEnum.SUCCESS || status == TaskStatusEnum.FAILED
                    || status == TaskStatusEnum.CANCELLED || status == TaskStatusEnum.ROLLED_BACK;
            assertThat(status.isTerminal())
                    .as("%s 终态判定", status)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("CVE 严重度序：NONE < LOW < MEDIUM < HIGH < CRITICAL")
    void cveSeverityRankIsOrdered() {
        CveSeverityEnum[] ordered = {CveSeverityEnum.NONE, CveSeverityEnum.LOW, CveSeverityEnum.MEDIUM,
                CveSeverityEnum.HIGH, CveSeverityEnum.CRITICAL};
        for (int i = 0; i < ordered.length; i++) {
            assertThat(ordered[i].severityRank()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("valueOf 按名解析无歧义")
    void valueOfResolves() {
        assertThat(TaskStatusEnum.valueOf("ROLLING_BACK")).isEqualTo(TaskStatusEnum.ROLLING_BACK);
        assertThat(InstallStatusEnum.valueOf("INSTALLING")).isEqualTo(InstallStatusEnum.INSTALLING);
        assertThat(CommandStatusEnum.valueOf("TIMEOUT")).isEqualTo(CommandStatusEnum.TIMEOUT);
        assertThat(ProjectTypeEnum.valueOf("MIXED")).isEqualTo(ProjectTypeEnum.MIXED);
        assertThat(CveSeverityEnum.valueOf("CRITICAL")).isEqualTo(CveSeverityEnum.CRITICAL);
        assertThat(ScopeEnum.valueOf("GLOBAL")).isEqualTo(ScopeEnum.GLOBAL);
        assertThat(LanguageEnum.valueOf("JAVA")).isEqualTo(LanguageEnum.JAVA);
        assertThat(ArchEnum.valueOf("ARM64")).isEqualTo(ArchEnum.ARM64);
        assertThat(OsTypeEnum.valueOf("WINDOWS")).isEqualTo(OsTypeEnum.WINDOWS);
    }
}
