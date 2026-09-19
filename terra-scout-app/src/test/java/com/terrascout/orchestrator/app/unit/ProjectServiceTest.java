package com.terrascout.orchestrator.app.unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.repository.ProjectRepository;
import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.AuditService;
import com.terrascout.orchestrator.app.service.ProjectService;
import com.terrascout.orchestrator.core.domain.Project;
import com.terrascout.orchestrator.core.dto.AnalyzeRequest;
import com.terrascout.orchestrator.core.dto.AnalyzeResponse;
import com.terrascout.orchestrator.core.dto.InstallPlan;
import com.terrascout.orchestrator.core.dto.ProjectConstraint;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.parser.ConstraintExtractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目导入分析单测：根目录无声明文件时采纳唯一子目录候选为项目根；
 * 无候选 / 多候选时 422001 携带 selectedPath / candidates / hint 明细；根级声明文件保持原根；
 * 分析/详情返回完整画像（type / constraints / 预览计划）。
 */
class ProjectServiceTest {

    @TempDir
    Path tmp;

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ConstraintExtractor constraintExtractor = mock(ConstraintExtractor.class);
    private final SdkVersionRepository sdkVersionRepository = mock(SdkVersionRepository.class);
    private final SdkInstallRecordRepository installRecordRepository = mock(SdkInstallRecordRepository.class);
    private final ProjectService service = new ProjectService(repository, auditService,
            new ObjectMapper(), constraintExtractor, sdkVersionRepository, installRecordRepository);

    private AnalyzeRequest requestFor(Path path) {
        AnalyzeRequest request = new AnalyzeRequest();
        request.setPath(path.toString());
        return request;
    }

    private ProjectConstraint javaConstraint() {
        ProjectConstraint constraint = new ProjectConstraint();
        constraint.setLanguage(LanguageEnum.JAVA);
        constraint.setConstraint("17");
        constraint.setSourceFile("pom.xml");
        constraint.setConfidence(1.0);
        return constraint;
    }

    @BeforeEach
    void stubSdkSources() {
        // 元数据与安装记录为空：预览计划仅产出 planId（匹配失败跳过，不阻断导入/详情）
        when(sdkVersionRepository.findByLanguageAndOsAndArch(any(), any(), any())).thenReturn(List.of());
        when(installRecordRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void analyzeKeepsRootWhenManifestAtSelectedRoot() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("maven-app"));
        Files.createFile(root.resolve("pom.xml"));
        when(constraintExtractor.extract(any())).thenReturn(List.of(javaConstraint()));
        when(repository.findByProjectRootPath(anyString())).thenReturn(Optional.empty());
        when(repository.existsById(anyString())).thenReturn(false);

        AnalyzeResponse response = service.analyze(requestFor(root));

        assertThat(response.getName()).isEqualTo("maven-app");
        assertThat(response.getType()).isEqualTo(ProjectTypeEnum.MAVEN);
        verify(constraintExtractor).extract(eq(root));
        verify(repository).save(argThat(p -> p.getProjectRootPath().equals(root.toString())));
    }

    @Test
    void analyzeAdoptsSingleNestedCandidateAsProjectRoot() throws Exception {
        Path selected = Files.createDirectory(tmp.resolve("workspace"));
        Path proj = Files.createDirectory(selected.resolve("proj"));
        Files.createFile(proj.resolve("pom.xml"));
        when(constraintExtractor.extract(any())).thenReturn(List.of(javaConstraint()));
        when(repository.findByProjectRootPath(anyString())).thenReturn(Optional.empty());
        when(repository.existsById(anyString())).thenReturn(false);

        AnalyzeResponse response = service.analyze(requestFor(selected));

        assertThat(response.getName()).isEqualTo("proj");
        assertThat(response.getType()).isEqualTo(ProjectTypeEnum.MAVEN);
        verify(constraintExtractor).extract(eq(proj));
        verify(repository).save(argThat(p -> p.getProjectRootPath().equals(proj.toString())));
    }

    @Test
    void analyzeRejectsUnrecognizedWithActionableDetails() throws Exception {
        Path selected = Files.createDirectory(tmp.resolve("nowhere"));
        Files.createDirectory(selected.resolve("plain"));

        assertThatThrownBy(() -> service.analyze(requestFor(selected)))
                .isInstanceOfSatisfying(TerraScoutException.class, e -> {
                    assertThat(e.getError()).isEqualTo(TerraScoutError.PROJECT_TYPE_UNKNOWN);
                    Map<String, Object> details = e.getDetails();
                    assertThat(details).isNotNull();
                    assertThat(details.get("selectedPath")).isEqualTo(selected.toString());
                    assertThat(details.get("hint")).asString().contains("pom.xml");
                    assertThat(details.get("candidates")).asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
                });
        verify(repository, never()).save(any());
    }

    @Test
    void analyzeRejectsAmbiguousNestedCandidates() throws Exception {
        Path selected = Files.createDirectory(tmp.resolve("mono"));
        Files.createFile(Files.createDirectory(selected.resolve("a")).resolve("pom.xml"));
        Files.createFile(Files.createDirectory(selected.resolve("b")).resolve("package.json"));

        assertThatThrownBy(() -> service.analyze(requestFor(selected)))
                .isInstanceOfSatisfying(TerraScoutException.class, e -> {
                    assertThat(e.getError()).isEqualTo(TerraScoutError.PROJECT_TYPE_UNKNOWN);
                    Map<String, Object> details = e.getDetails();
                    assertThat(details).isNotNull();
                    assertThat(details.get("candidates")).asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(2);
                });
        verify(repository, never()).save(any());
    }

    // ---- 完整画像与预览计划 ----

    @Test
    void analyzePersistsProfileWithTypeAndReturnsPreviewPlan() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("npm-app"));
        Files.createFile(root.resolve("package.json"));
        when(constraintExtractor.extract(any())).thenReturn(List.of(javaConstraint()));
        when(repository.findByProjectRootPath(anyString())).thenReturn(Optional.empty());
        when(repository.existsById(anyString())).thenReturn(false);

        AnalyzeResponse response = service.analyze(requestFor(root));

        // profile_json 升级为 {type, constraints} 结构
        verify(repository).save(argThat(p -> p.getProfileJson().contains("\"type\":\"NPM\"")));
        // 分析响应携带预览计划（planId=projectId；元数据为空 → sdkInstalls 空但计划可用）
        assertThat(response.getPlan()).isNotNull();
        assertThat(response.getPlan().getPlanId()).isEqualTo(response.getProjectId());
        assertThat(response.getPlan().getSdkInstalls()).isEmpty();
    }

    @Test
    void detailItemReturnsTypeConstraintsAndPreviewPlan() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("npm-detail"));
        Files.createFile(root.resolve("package.json"));
        Project project = new Project();
        project.setId("p-1");
        project.setName("npm-detail");
        project.setProjectRootPath(root.toString());
        project.setProfileJson("{\"type\":\"NPM\",\"constraints\":[{\"language\":\"NODE\","
                + "\"constraint\":\">=20.0.0\",\"sourceFile\":\"package.json\",\"confidence\":1.0}]}");
        when(repository.findById("p-1")).thenReturn(Optional.of(project));

        Map<String, Object> item = service.detailItem("p-1");

        assertThat(item.get("type")).isEqualTo(ProjectTypeEnum.NPM);
        assertThat(item.get("constraints")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        InstallPlan plan = (InstallPlan) item.get("plan");
        assertThat(plan.getPlanId()).isEqualTo("p-1");
    }

    @Test
    void legacyArrayProfileFallsBackToDiskRedetect() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("maven-legacy"));
        Files.createFile(root.resolve("pom.xml"));
        Project project = new Project();
        project.setId("p-2");
        project.setName("maven-legacy");
        project.setProjectRootPath(root.toString());
        // 旧版 profile_json：纯约束数组
        project.setProfileJson("[{\"language\":\"JAVA\",\"constraint\":\"17\","
                + "\"sourceFile\":\"pom.xml\",\"confidence\":1.0}]");
        when(constraintExtractor.extract(any())).thenReturn(List.of(javaConstraint()));

        Map<String, Object> item = service.item(project, true);

        assertThat(item.get("type")).isEqualTo(ProjectTypeEnum.MAVEN);
        assertThat(item.get("constraints")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
    }
}
