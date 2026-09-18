package com.terrascout.orchestrator.core.enums;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 枚举三表同步断言（master-plan §0：ddl-migration 2.4 ↔ openapi.yaml ↔ core 枚举类）。
 *
 * <p>任何一方漂移（DDL 改枚举值 / openapi 改枚举 / core 改枚举）在此立即暴露。
 */
class EnumsSyncTest {

    private static List<String> names(Enum<?>[] values) {
        return Stream.of(values).map(Enum::name).collect(Collectors.toList());
    }

    @Test
    @DisplayName("LanguageEnum = ddl 2.4 sdk_version.language")
    void languageEnumMatchesDdl() {
        assertThat(names(LanguageEnum.values())).containsExactly("JAVA", "NODE", "PYTHON", "GO");
    }

    @Test
    @DisplayName("OsTypeEnum = ddl 2.4 project.os_type / sdk_version.os")
    void osTypeEnumMatchesDdl() {
        assertThat(names(OsTypeEnum.values())).containsExactly("WINDOWS", "MACOS", "LINUX");
    }

    @Test
    @DisplayName("ArchEnum = ddl 2.4 sdk_version.arch")
    void archEnumMatchesDdl() {
        assertThat(names(ArchEnum.values())).containsExactly("AMD64", "ARM64");
    }

    @Test
    @DisplayName("TaskStatusEnum = ddl 2.4 task.status（9 状态）")
    void taskStatusEnumMatchesDdl() {
        assertThat(names(TaskStatusEnum.values())).containsExactly(
                "PENDING", "QUEUED", "RUNNING", "PAUSED",
                "SUCCESS", "FAILED", "CANCELLED", "ROLLING_BACK", "ROLLED_BACK");
    }

    @Test
    @DisplayName("TaskTypeEnum = ddl 2.4 task.task_type")
    void taskTypeEnumMatchesDdl() {
        assertThat(names(TaskTypeEnum.values())).containsExactly(
                "PROJECT_ASSEMBLE", "SDK_INSTALL", "SDK_UNINSTALL", "DEPENDENCY_INSTALL", "VERIFY_PROJECT");
    }

    @Test
    @DisplayName("StepStatusEnum = ddl 2.4 task_step.status")
    void stepStatusEnumMatchesDdl() {
        assertThat(names(StepStatusEnum.values())).containsExactly(
                "PENDING", "RUNNING", "SUCCESS", "FAILED", "SKIPPED", "ROLLED_BACK");
    }

    @Test
    @DisplayName("ScopeEnum = ddl 2.4 sdk_install_record.scope")
    void scopeEnumMatchesDdl() {
        assertThat(names(ScopeEnum.values())).containsExactly("GLOBAL", "PROJECT");
    }

    @Test
    @DisplayName("InstallStatusEnum = ddl 2.4 sdk_install_record.status")
    void installStatusEnumMatchesDdl() {
        assertThat(names(InstallStatusEnum.values())).containsExactly(
                "INSTALLING", "SUCCESS", "FAILED", "ROLLED_BACK");
    }

    @Test
    @DisplayName("CommandStatusEnum = ddl 2.4 command_execution.status")
    void commandStatusEnumMatchesDdl() {
        assertThat(names(CommandStatusEnum.values())).containsExactly(
                "RUNNING", "SUCCESS", "FAILED", "TIMEOUT");
    }

    @Test
    @DisplayName("CveSeverityEnum = ddl 2.4 sdk_version.highest_cve_severity（D-017）")
    void cveSeverityEnumMatchesDdl() {
        assertThat(names(CveSeverityEnum.values())).containsExactly(
                "NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    }

    @Test
    @DisplayName("ProjectTypeEnum = openapi AnalyzeResponse.type")
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
