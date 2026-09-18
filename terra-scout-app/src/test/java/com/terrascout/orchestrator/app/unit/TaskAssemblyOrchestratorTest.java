package com.terrascout.orchestrator.app.unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.terrascout.orchestrator.app.repository.ProjectRepository;
import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.SdkInstaller;
import com.terrascout.orchestrator.app.service.TaskAssemblyOrchestrator;
import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.domain.Project;
import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.dto.ProjectConstraint;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.CveSeverityEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;
import com.terrascout.orchestrator.env.EnvInjector;
import com.terrascout.orchestrator.env.EnvScriptGenerator;
import com.terrascout.orchestrator.env.ProcessExecutor;
import com.terrascout.orchestrator.parser.ConstraintExtractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 装配编排器单测：把 TaskEngine 与 download/env 模块真实接入的成功 / 失败 / 计划缺失路径。
 */
class TaskAssemblyOrchestratorTest {

    @TempDir
    Path projectRoot;

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final TaskJournal journal = mock(TaskJournal.class);
    private final ConstraintExtractor constraintExtractor = mock(ConstraintExtractor.class);
    private final SdkVersionRepository sdkVersionRepository = mock(SdkVersionRepository.class);
    private final SdkInstallRecordRepository installRecordRepository = mock(SdkInstallRecordRepository.class);
    private final SdkInstaller sdkInstaller = mock(SdkInstaller.class);
    private final ProcessExecutor processExecutor = mock(ProcessExecutor.class);
    private final EnvInjector envInjector = mock(EnvInjector.class);
    private final EnvScriptGenerator envScriptGenerator = mock(EnvScriptGenerator.class);

    private final TaskAssemblyOrchestrator orchestrator = new TaskAssemblyOrchestrator(
            projectRepository, journal, constraintExtractor, sdkVersionRepository,
            installRecordRepository, sdkInstaller, processExecutor, envInjector, envScriptGenerator);

    private Project project() {
        Project project = new Project();
        project.setId("p1");
        project.setProjectRootPath(projectRoot.toString());
        return project;
    }

    private SdkVersion sdkVersion() {
        SdkVersion v = new SdkVersion();
        v.setLanguage(LanguageEnum.JAVA);
        v.setVersion("17.0.9");
        v.setOs(OsTypeEnum.WINDOWS);
        v.setArch(ArchEnum.AMD64);
        v.setEol(false);
        v.setLts(true);
        v.setHighestCveSeverity(CveSeverityEnum.NONE);
        v.setCveCount(0);
        v.setDownloadUrl("https://example.com/jdk.zip");
        v.setSha256("d".repeat(64));
        return v;
    }

    private SdkInstallRecord record() {
        SdkInstallRecord r = new SdkInstallRecord();
        r.setId("r1");
        r.setLanguage(LanguageEnum.JAVA);
        r.setVersion("17.0.9");
        r.setInstallPath("C:\\fake\\17.0.9");
        return r;
    }

    private static ProjectConstraint javaConstraint(String constraint) {
        ProjectConstraint c = new ProjectConstraint();
        c.setLanguage(LanguageEnum.JAVA);
        c.setConstraint(constraint);
        c.setSourceFile("pom.xml");
        c.setConfidence(1.0);
        return c;
    }

    @Test
    void runCompletesSuccessfully() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project()));
        when(constraintExtractor.extract(any())).thenReturn(List.of(javaConstraint("17")));
        when(sdkVersionRepository.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(sdkVersion()));
        when(installRecordRepository.findAll()).thenReturn(List.of());
        when(sdkInstaller.ensureInstalled(any(SdkVersion.class), any(), eq("p1"))).thenReturn(record());
        when(processExecutor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", "", 0L));
        when(envInjector.buildEnv(any(), any())).thenReturn(Map.of("java", "C:\\fake"));

        orchestrator.run("t1", "p1", List.of());
        verify(journal).complete(eq("t1"), eq(TaskStatusEnum.SUCCESS), eq(100.0), eq(null), eq(null));
    }

    @Test
    void runFailsOnVersionMatch() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project()));
        when(constraintExtractor.extract(any())).thenReturn(List.of(javaConstraint("99")));
        when(sdkVersionRepository.findByLanguageAndOsAndArch(any(), any(), any())).thenReturn(List.of());
        when(installRecordRepository.findAll()).thenReturn(List.of());

        orchestrator.run("t1", "p1", List.of());
        verify(journal).complete(eq("t1"), eq(TaskStatusEnum.ROLLED_BACK), eq(0.0), eq(422006), anyString());
    }

    @Test
    void runFailsWhenProjectMissing() {
        when(projectRepository.findById("missing")).thenReturn(Optional.empty());
        orchestrator.run("t1", "missing", List.of());
        verify(journal).complete(eq("t1"), eq(TaskStatusEnum.FAILED), eq(0.0), eq(404003), anyString());
    }
}
