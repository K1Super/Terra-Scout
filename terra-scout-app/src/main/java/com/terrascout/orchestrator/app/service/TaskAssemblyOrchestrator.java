package com.terrascout.orchestrator.app.service;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.app.repository.ProjectRepository;
import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.step.AssemblySession;
import com.terrascout.orchestrator.app.step.BindEnvStep;
import com.terrascout.orchestrator.app.step.CreateIsolationStep;
import com.terrascout.orchestrator.app.step.DetectProjectStep;
import com.terrascout.orchestrator.app.step.InstallDependenciesStep;
import com.terrascout.orchestrator.app.step.InstallSdkStep;
import com.terrascout.orchestrator.app.step.MatchVersionStep;
import com.terrascout.orchestrator.app.step.ParseManifestStep;
import com.terrascout.orchestrator.app.step.VerifyProjectStep;
import com.terrascout.orchestrator.core.domain.Project;
import com.terrascout.orchestrator.core.dto.VersionOverride;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.env.EnvInjector;
import com.terrascout.orchestrator.env.EnvScriptGenerator;
import com.terrascout.orchestrator.env.ProcessExecutor;
import com.terrascout.orchestrator.parser.ConstraintExtractor;
import com.terrascout.orchestrator.task.TaskEngine;
import com.terrascout.orchestrator.task.TaskStepDefinition;
import com.terrascout.orchestrator.task.TaskStepExecutor;

import org.springframework.stereotype.Service;

/**
 * 装配编排器：把 TaskEngine 与 download/env 模块真实接入，驱动 11 步四语言装配并把状态落库。
 *
 * <p>由 {@link TaskService#execute} 在事务提交后异步调用（异步语义）；引擎仅同步阻塞，
 * 最终状态经 session.errorCode（正常失败）或 result.errorCode（回滚失败 500004）回写 task。
 */
@Service
public class TaskAssemblyOrchestrator {

    private static final int MAX_RETRY = 3;

    private final ProjectRepository projectRepository;
    private final TaskJournal journal;
    private final ConstraintExtractor constraintExtractor;
    private final SdkVersionRepository sdkVersionRepository;
    private final SdkInstallRecordRepository installRecordRepository;
    private final SdkInstaller sdkInstaller;
    private final ProcessExecutor processExecutor;
    private final EnvInjector envInjector;
    private final EnvScriptGenerator envScriptGenerator;

    public TaskAssemblyOrchestrator(ProjectRepository projectRepository,
                                    TaskJournal journal,
                                    ConstraintExtractor constraintExtractor,
                                    SdkVersionRepository sdkVersionRepository,
                                    SdkInstallRecordRepository installRecordRepository,
                                    SdkInstaller sdkInstaller,
                                    ProcessExecutor processExecutor,
                                    EnvInjector envInjector,
                                    EnvScriptGenerator envScriptGenerator) {
        this.projectRepository = projectRepository;
        this.journal = journal;
        this.constraintExtractor = constraintExtractor;
        this.sdkVersionRepository = sdkVersionRepository;
        this.installRecordRepository = installRecordRepository;
        this.sdkInstaller = sdkInstaller;
        this.processExecutor = processExecutor;
        this.envInjector = envInjector;
        this.envScriptGenerator = envScriptGenerator;
    }

    /** 执行一条装配任务（异步线程调用；versionOverrides 为用户 SDK 选配）。 */
    public void run(String taskId, String projectId, List<VersionOverride> versionOverrides) {
        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new TerraScoutException(TerraScoutError.PLAN_NOT_FOUND,
                            "装配计划不存在: " + projectId));
            AssemblySession session = newSession(taskId, project);
            session.setVersionOverrides(versionOverrides);
            TaskEngine.TaskStepResolver resolver = buildResolver(session);

            journal.markRunning(taskId, Thread.currentThread().getName());
            try (TaskEngine engine = new TaskEngine()) {
                TaskEngine.TaskExecutionResult result = engine.execute(
                        projectId, TaskStepDefinition.projectAssemble(), resolver,
                        () -> journal.heartbeat(taskId), MAX_RETRY);
                complete(session, result);
            }
        } catch (TerraScoutException e) {
            journal.complete(taskId, TaskStatusEnum.FAILED, 0.0, e.getError().getCode(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            journal.complete(taskId, TaskStatusEnum.FAILED, 0.0,
                    TerraScoutError.UNKNOWN.getCode(), "任务执行被中断");
        } catch (RuntimeException e) {
            journal.complete(taskId, TaskStatusEnum.FAILED, 0.0,
                    TerraScoutError.UNKNOWN.getCode(), e.getMessage());
        }
    }

    private AssemblySession newSession(String taskId, Project project) {
        AssemblySession session = new AssemblySession();
        session.setTaskId(taskId);
        session.setProjectId(project.getId());
        session.setProjectRoot(Paths.get(project.getProjectRootPath()));
        return session;
    }

    private void complete(AssemblySession session, TaskEngine.TaskExecutionResult result) {
        TaskStatusEnum status = result.status();
        double progress = status == TaskStatusEnum.SUCCESS ? 100.0 : 0.0;
        Integer errorCode = result.errorCode() != null ? result.errorCode() : session.getErrorCode();
        journal.complete(session.getTaskId(), status, progress, errorCode, session.getErrorMsg());
    }

    private TaskEngine.TaskStepResolver buildResolver(AssemblySession session) {
        List<TaskStepDefinition> definitions = TaskStepDefinition.projectAssemble();
        Map<String, TaskStepExecutor> executors = new HashMap<>();
        for (int i = 0; i < definitions.size(); i++) {
            TaskStepDefinition def = definitions.get(i);
            executors.put(def.name(), buildStep(def, i, session));
        }
        return executors::get;
    }

    private TaskStepExecutor buildStep(TaskStepDefinition def, int index, AssemblySession session) {
        switch (def.name()) {
            case TaskStepDefinition.DETECT_PROJECT:
                return new DetectProjectStep(journal, session, def, index);
            case TaskStepDefinition.PARSE_MANIFEST:
                return new ParseManifestStep(journal, session, def, index, constraintExtractor);
            case TaskStepDefinition.MATCH_VERSION:
                return new MatchVersionStep(journal, session, def, index,
                        sdkVersionRepository, installRecordRepository);
            case TaskStepDefinition.INSTALL_SDK_JAVA:
                return new InstallSdkStep(journal, session, def, index,
                        sdkInstaller, sdkVersionRepository, LanguageEnum.JAVA);
            case TaskStepDefinition.INSTALL_SDK_NODE:
                return new InstallSdkStep(journal, session, def, index,
                        sdkInstaller, sdkVersionRepository, LanguageEnum.NODE);
            case TaskStepDefinition.INSTALL_SDK_GO:
                return new InstallSdkStep(journal, session, def, index,
                        sdkInstaller, sdkVersionRepository, LanguageEnum.GO);
            case TaskStepDefinition.INSTALL_SDK_PYTHON:
                return new InstallSdkStep(journal, session, def, index,
                        sdkInstaller, sdkVersionRepository, LanguageEnum.PYTHON);
            case TaskStepDefinition.CREATE_ISOLATION:
                return new CreateIsolationStep(journal, session, def, index);
            case TaskStepDefinition.INSTALL_DEPENDENCIES:
                return new InstallDependenciesStep(journal, session, def, index,
                        processExecutor, envInjector);
            case TaskStepDefinition.BIND_ENV:
                return new BindEnvStep(journal, session, def, index, envScriptGenerator);
            case TaskStepDefinition.VERIFY_PROJECT:
                return new VerifyProjectStep(journal, session, def, index, processExecutor);
            default:
                throw new IllegalStateException("未注册的步骤执行器: " + def.name());
        }
    }
}
