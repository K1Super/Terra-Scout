package com.terrascout.orchestrator.app.step;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.SdkInstaller;
import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.dto.ProjectConstraint;
import com.terrascout.orchestrator.core.dto.SdkInstallItem;
import com.terrascout.orchestrator.core.dto.VersionOverride;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.CveSeverityEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.env.EnvInjector;
import com.terrascout.orchestrator.env.EnvScriptGenerator;
import com.terrascout.orchestrator.env.ProcessExecutor;
import com.terrascout.orchestrator.parser.ConstraintExtractor;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 8 类步骤执行器单测：覆盖前向执行（SUCCESS/SKIPPED/FAILED）与回滚/审计路径。
 */
class StepExecutorsTest {

    @TempDir
    Path projectRoot;

    @TempDir
    Path emptyDir;

    private final TaskJournal journal = mock(TaskJournal.class);

    private static TaskStepDefinition def(String name, boolean hasRollback) {
        return new TaskStepDefinition(name, 10, false, true, hasRollback);
    }

    private static AssemblySession session(Path root) {
        AssemblySession session = new AssemblySession();
        session.setTaskId("t1");
        session.setProjectId("p1");
        session.setProjectRoot(root);
        return session;
    }

    private static ProjectConstraint javaConstraint(String constraint) {
        ProjectConstraint c = new ProjectConstraint();
        c.setLanguage(LanguageEnum.JAVA);
        c.setConstraint(constraint);
        c.setSourceFile("pom.xml");
        c.setConfidence(1.0);
        return c;
    }

    private static SdkVersion sdkVersion(String version) {
        SdkVersion v = new SdkVersion();
        v.setLanguage(LanguageEnum.JAVA);
        v.setVersion(version);
        v.setOs(OsTypeEnum.WINDOWS);
        v.setArch(ArchEnum.AMD64);
        v.setEol(false);
        v.setLts(true);
        v.setHighestCveSeverity(CveSeverityEnum.NONE);
        v.setCveCount(0);
        v.setDownloadUrl("https://example.com/jdk.zip");
        v.setSha256("c".repeat(64));
        return v;
    }

    private static SdkInstallItem installItem(String version) {
        SdkInstallItem item = new SdkInstallItem();
        item.setLanguage(LanguageEnum.JAVA);
        item.setVersion(version);
        item.setAction(SdkInstallItem.Action.INSTALL);
        return item;
    }

    private static SdkInstallRecord record(String path) {
        SdkInstallRecord r = new SdkInstallRecord();
        r.setId("r1");
        r.setLanguage(LanguageEnum.JAVA);
        r.setVersion("17.0.9");
        r.setInstallPath(path);
        return r;
    }

    @Test
    void detectProjectMaven() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        AssemblySession session = session(projectRoot);
        DetectProjectStep step = new DetectProjectStep(journal, session, def("DETECT_PROJECT", false), 0);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        assertThat(session.getProjectType()).isEqualTo(ProjectTypeEnum.MAVEN);
    }

    @Test
    void detectProjectUnknownFails() {
        AssemblySession session = session(emptyDir);
        DetectProjectStep step = new DetectProjectStep(journal, session, def("DETECT_PROJECT", false), 0);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.FAILED);
        assertThat(session.getErrorCode()).isEqualTo(422001);
    }

    @Test
    void parseManifestStoresConstraints() {
        ConstraintExtractor extractor = mock(ConstraintExtractor.class);
        when(extractor.extract(projectRoot)).thenReturn(List.of(javaConstraint("17")));
        AssemblySession session = session(projectRoot);
        ParseManifestStep step = new ParseManifestStep(journal, session,
                def("PARSE_MANIFEST", false), 1, extractor);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        assertThat(session.getConstraints()).hasSize(1);
    }

    @Test
    void matchVersionBuildsPlan() {
        SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
        SdkInstallRecordRepository recordRepo = mock(SdkInstallRecordRepository.class);
        when(recordRepo.findAll()).thenReturn(List.of());
        when(versionRepo.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(sdkVersion("17.0.9")));
        AssemblySession session = session(projectRoot);
        session.setConstraints(List.of(javaConstraint("17")));
        MatchVersionStep step = new MatchVersionStep(journal, session,
                def("MATCH_VERSION", false), 2, versionRepo, recordRepo);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        assertThat(session.getSdkPlan().get(LanguageEnum.JAVA).getVersion()).isEqualTo("17.0.9");
        assertThat(session.getPlan().getPlanId()).isEqualTo("p1");
    }

    @Test
    void matchVersionAppliesUserOverride() {
        SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
        SdkInstallRecordRepository recordRepo = mock(SdkInstallRecordRepository.class);
        when(recordRepo.findAll()).thenReturn(List.of());
        when(versionRepo.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(sdkVersion("17.0.9"), sdkVersion("17.0.10")));
        AssemblySession session = session(projectRoot);
        session.setConstraints(List.of(javaConstraint("17")));
        VersionOverride override = new VersionOverride();
        override.setLanguage(LanguageEnum.JAVA);
        override.setVersion("17.0.10");
        session.setVersionOverrides(List.of(override));

        MatchVersionStep step = new MatchVersionStep(journal, session,
                def("MATCH_VERSION", false), 2, versionRepo, recordRepo);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        assertThat(session.getSdkPlan().get(LanguageEnum.JAVA).getVersion()).isEqualTo("17.0.10");
        assertThat(session.getSdkPlan().get(LanguageEnum.JAVA).getReason()).contains("用户选配版本 17.0.10");
        assertThat(session.getSdkPlan().get(LanguageEnum.JAVA).getCandidates()).hasSize(2);
    }

    @Test
    void matchVersionOverrideUnknownVersionFails() {
        SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
        SdkInstallRecordRepository recordRepo = mock(SdkInstallRecordRepository.class);
        when(recordRepo.findAll()).thenReturn(List.of());
        when(versionRepo.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(sdkVersion("17.0.9")));
        AssemblySession session = session(projectRoot);
        session.setConstraints(List.of(javaConstraint("17")));
        VersionOverride override = new VersionOverride();
        override.setLanguage(LanguageEnum.JAVA);
        override.setVersion("17.0.99");
        session.setVersionOverrides(List.of(override));

        MatchVersionStep step = new MatchVersionStep(journal, session,
                def("MATCH_VERSION", false), 2, versionRepo, recordRepo);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.FAILED);
        assertThat(session.getErrorCode()).isEqualTo(422006);
    }

    @Test
    void matchVersionNoMatchFails() {
        SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
        SdkInstallRecordRepository recordRepo = mock(SdkInstallRecordRepository.class);
        when(recordRepo.findAll()).thenReturn(List.of());
        when(versionRepo.findByLanguageAndOsAndArch(any(), any(), any())).thenReturn(List.of());
        AssemblySession session = session(projectRoot);
        session.setConstraints(List.of(javaConstraint("99")));
        MatchVersionStep step = new MatchVersionStep(journal, session,
                def("MATCH_VERSION", false), 2, versionRepo, recordRepo);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.FAILED);
        assertThat(session.getErrorCode()).isEqualTo(422006);
    }

    @Test
    void installSdkReusesExisting() {
        SdkInstaller installer = mock(SdkInstaller.class);
        SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
        when(installer.findInstalled(LanguageEnum.JAVA, "17.0.9"))
                .thenReturn(Optional.of(record("C:\\fake\\17.0.9")));
        AssemblySession session = session(projectRoot);
        SdkInstallItem item = installItem("17.0.9");
        item.setAction(SdkInstallItem.Action.REUSE);
        session.getSdkPlan().put(LanguageEnum.JAVA, item);

        InstallSdkStep step = new InstallSdkStep(journal, session,
                def("INSTALL_SDK_JAVA", true), 3, installer, versionRepo, LanguageEnum.JAVA);
        TaskStepContext ctx = new TaskStepContext();
        assertThat(step.execute(ctx)).isEqualTo(StepStatusEnum.SKIPPED);
        assertThat(session.getSdkHomes().get("java")).isEqualTo("C:\\fake\\17.0.9");
        assertThat((Boolean) ctx.getRollbackData().get("reuse")).isTrue();
    }

    @Test
    void installSdkInstallsFresh() {
        SdkInstaller installer = mock(SdkInstaller.class);
        SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
        when(versionRepo.findByLanguageAndOsAndArch(any(), any(), any()))
                .thenReturn(List.of(sdkVersion("17.0.9")));
        when(installer.ensureInstalled(any(SdkVersion.class), any(), eq("p1")))
                .thenReturn(record("C:\\fake\\17.0.9"));
        AssemblySession session = session(projectRoot);
        session.getSdkPlan().put(LanguageEnum.JAVA, installItem("17.0.9"));

        InstallSdkStep step = new InstallSdkStep(journal, session,
                def("INSTALL_SDK_JAVA", true), 3, installer, versionRepo, LanguageEnum.JAVA);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        assertThat(session.getSdkHomes().get("java")).isEqualTo("C:\\fake\\17.0.9");
    }

    @Test
    void installSdkRollbackSkipsReuse() {
        SdkInstaller installer = mock(SdkInstaller.class);
        SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
        InstallSdkStep step = new InstallSdkStep(journal, session(projectRoot),
                def("INSTALL_SDK_JAVA", true), 3, installer, versionRepo, LanguageEnum.JAVA);
        TaskStepContext ctx = new TaskStepContext();
        ctx.getRollbackData().put("reuse", true);
        step.rollback(ctx);
        verify(journal, never()).stepRolledBack(any(), anyInt());
    }

    @Test
    void installSdkRollbackDeletesProjectRecord() {
        SdkInstaller installer = mock(SdkInstaller.class);
        SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
        InstallSdkStep step = new InstallSdkStep(journal, session(projectRoot),
                def("INSTALL_SDK_JAVA", true), 3, installer, versionRepo, LanguageEnum.JAVA);
        TaskStepContext ctx = new TaskStepContext();
        ctx.getRollbackData().put("reuse", false);
        ctx.getRollbackData().put("version", "17.0.9");
        step.rollback(ctx);
        verify(installer).rollback(LanguageEnum.JAVA, "17.0.9", "p1");
    }

    @Test
    void createIsolationWritesMarkerAndRollsBack() {
        AssemblySession session = session(projectRoot);
        CreateIsolationStep step = new CreateIsolationStep(journal, session,
                def("CREATE_ISOLATION", true), 5);
        TaskStepContext ctx = new TaskStepContext();
        assertThat(step.execute(ctx)).isEqualTo(StepStatusEnum.SUCCESS);
        assertThat(session.getIsolationDir()).isNotNull();
        assertThat(Files.exists(projectRoot.resolve(".devenv").resolve(".terra-scout"))).isTrue();

        step.rollback(ctx);
        assertThat(Files.exists(session.getIsolationDir())).isFalse();
    }

    @Test
    void installDependenciesRunsMavenInstall() {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        EnvInjector injector = mock(EnvInjector.class);
        when(injector.buildEnv(any(), any())).thenReturn(Map.of("java", "C:\\fake"));
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.MAVEN);
        InstallDependenciesStep step = new InstallDependenciesStep(journal, session,
                def("INSTALL_DEPENDENCIES", true), 6, executor, injector);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        assertThat(session.getEnv()).containsEntry("java", "C:\\fake");
    }

    @Test
    void installDependenciesNonZeroExitFails() {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        EnvInjector injector = mock(EnvInjector.class);
        when(injector.buildEnv(any(), any())).thenReturn(Map.of());
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.MAVEN);
        InstallDependenciesStep step = new InstallDependenciesStep(journal, session,
                def("INSTALL_DEPENDENCIES", true), 6, executor, injector);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.FAILED);
        assertThat(session.getErrorCode()).isEqualTo(422012);
    }

    @Test
    void installDependenciesGoRunsGoModDownload() throws Exception {
        Files.writeString(projectRoot.resolve("go.mod"), "module example\n\ngo 1.21\n");
        ProcessExecutor executor = mock(ProcessExecutor.class);
        EnvInjector injector = mock(EnvInjector.class);
        when(injector.buildEnv(any(), any())).thenReturn(Map.of());
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.GO);
        InstallDependenciesStep step = new InstallDependenciesStep(journal, session,
                def("INSTALL_DEPENDENCIES", true), 6, executor, injector);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        ArgumentCaptor<CommandSpec> captor = ArgumentCaptor.forClass(CommandSpec.class);
        verify(executor).execute(captor.capture(), any(), any());
        assertThat(captor.getValue().getCommand()).isEqualTo("go");
        assertThat(captor.getValue().getArgs()).containsExactly("mod", "download");
    }

    @Test
    void installDependenciesPythonRunsPipInstall() throws Exception {
        Files.writeString(projectRoot.resolve("requirements.txt"), "requests==2.31.0\n");
        ProcessExecutor executor = mock(ProcessExecutor.class);
        EnvInjector injector = mock(EnvInjector.class);
        when(injector.buildEnv(any(), any())).thenReturn(Map.of());
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.PYTHON);
        InstallDependenciesStep step = new InstallDependenciesStep(journal, session,
                def("INSTALL_DEPENDENCIES", true), 6, executor, injector);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        ArgumentCaptor<CommandSpec> captor = ArgumentCaptor.forClass(CommandSpec.class);
        verify(executor).execute(captor.capture(), any(), any());
        assertThat(captor.getValue().getCommand()).isEqualTo("python");
        assertThat(captor.getValue().getArgs())
                .containsExactly("-m", "pip", "install", "-r", "requirements.txt");
    }

    @Test
    void installDependenciesPythonWithoutRequirementsSkips() {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        EnvInjector injector = mock(EnvInjector.class);
        when(injector.buildEnv(any(), any())).thenReturn(Map.of());
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.PYTHON);
        InstallDependenciesStep step = new InstallDependenciesStep(journal, session,
                def("INSTALL_DEPENDENCIES", true), 6, executor, injector);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        verify(executor, never()).execute(any(), any(), any());
    }

    @Test
    void bindEnvWritesScript() {
        EnvScriptGenerator generator = mock(EnvScriptGenerator.class);
        AssemblySession session = session(projectRoot);
        session.setEnv(Map.of("java", "C:\\fake"));
        BindEnvStep step = new BindEnvStep(journal, session, def("BIND_ENV", true), 7, generator);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        verify(generator).write(any(), eq(session.getEnv()));
    }

    @Test
    void verifyProjectRunsVerifyCommands() {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.NPM);
        VerifyProjectStep step = new VerifyProjectStep(journal, session,
                def("VERIFY_PROJECT", false), 8, executor);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
    }

    @Test
    void verifyProjectNonZeroExitFails() {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.MAVEN);
        VerifyProjectStep step = new VerifyProjectStep(journal, session,
                def("VERIFY_PROJECT", false), 8, executor);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.FAILED);
        assertThat(session.getErrorCode()).isEqualTo(422015);
    }

    @Test
    void verifyProjectGoRunsGoVersion() {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.GO);
        VerifyProjectStep step = new VerifyProjectStep(journal, session,
                def("VERIFY_PROJECT", false), 8, executor);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        ArgumentCaptor<CommandSpec> captor = ArgumentCaptor.forClass(CommandSpec.class);
        verify(executor).execute(captor.capture(), any(), any());
        assertThat(captor.getValue().getCommand()).isEqualTo("go");
        assertThat(captor.getValue().getArgs()).containsExactly("version");
    }

    @Test
    void verifyProjectPythonRunsPythonVersion() {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.PYTHON);
        VerifyProjectStep step = new VerifyProjectStep(journal, session,
                def("VERIFY_PROJECT", false), 8, executor);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        ArgumentCaptor<CommandSpec> captor = ArgumentCaptor.forClass(CommandSpec.class);
        verify(executor).execute(captor.capture(), any(), any());
        assertThat(captor.getValue().getCommand()).isEqualTo("python");
        assertThat(captor.getValue().getArgs()).containsExactly("--version");
    }

    @Test
    void verifyProjectMixedRunsAllFour() {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", "", 0L));
        AssemblySession session = session(projectRoot);
        session.setProjectType(ProjectTypeEnum.MIXED);
        VerifyProjectStep step = new VerifyProjectStep(journal, session,
                def("VERIFY_PROJECT", false), 8, executor);
        assertThat(step.execute(new TaskStepContext())).isEqualTo(StepStatusEnum.SUCCESS);
        ArgumentCaptor<CommandSpec> captor = ArgumentCaptor.forClass(CommandSpec.class);
        verify(executor, times(4)).execute(captor.capture(), any(), any());
        assertThat(captor.getAllValues())
                .extracting(CommandSpec::getCommand)
                .containsExactly("mvn", "node", "go", "python");
    }
}
