package com.terrascout.orchestrator.app.step;

import java.util.Locale;

import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.SdkInstaller;
import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.dto.SdkInstallItem;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;
import com.terrascout.orchestrator.core.enums.ScopeEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

/**
 * INSTALL_SDK_{JAVA,NODE,GO,PYTHON} 步骤（参数化语言）：复用已装版本（SKIPPED）或真实下载安装（SUCCESS）。
 *
 * <p>REUSE 无副作用，回滚时跳过（避免误删全局共享 SDK）；仅 INSTALL（本任务新建的 PROJECT 记录）
 * 才执行回滚删除。
 */
public class InstallSdkStep extends AbstractStepExecutor {

    private final SdkInstaller sdkInstaller;
    private final SdkVersionRepository sdkVersionRepository;
    private final LanguageEnum language;

    public InstallSdkStep(TaskJournal journal, AssemblySession session,
                          TaskStepDefinition definition, int stepIndex,
                          SdkInstaller sdkInstaller, SdkVersionRepository sdkVersionRepository,
                          LanguageEnum language) {
        super(journal, session, definition, stepIndex);
        this.sdkInstaller = sdkInstaller;
        this.sdkVersionRepository = sdkVersionRepository;
        this.language = language;
    }

    @Override
    protected StepStatusEnum doExecute(TaskStepContext context) {
        SdkInstallItem item = session.getSdkPlan().get(language);
        if (item == null) {
            context.getRollbackData().put("reuse", true);
            return StepStatusEnum.SKIPPED;
        }
        String homeKey = homeKey(language);
        if (item.getAction() == SdkInstallItem.Action.REUSE) {
            String path = sdkInstaller.findInstalled(language, item.getVersion())
                    .map(SdkInstallRecord::getInstallPath)
                    .orElseThrow(() -> new TerraScoutException(TerraScoutError.NO_SDK_VERSION_MATCH,
                            "复用版本未找到安装记录: " + item.getVersion()));
            session.getSdkHomes().put(homeKey, path);
            context.getRollbackData().put("reuse", true);
            context.getOutput().put("action", "REUSE");
            context.getOutput().put("sdkHome", path);
            return StepStatusEnum.SKIPPED;
        }
        SdkVersion version = resolveVersion(item.getVersion());
        SdkInstallRecord record = sdkInstaller.ensureInstalled(
                version, ScopeEnum.PROJECT, session.getProjectId());
        session.getSdkHomes().put(homeKey, record.getInstallPath());
        context.getRollbackData().put("reuse", false);
        context.getRollbackData().put("version", item.getVersion());
        context.getOutput().put("action", "INSTALL");
        context.getOutput().put("sdkHome", record.getInstallPath());
        return StepStatusEnum.SUCCESS;
    }

    @Override
    public void rollback(TaskStepContext context) {
        if (Boolean.TRUE.equals(context.getRollbackData().get("reuse"))) {
            return;
        }
        super.rollback(context);
    }

    @Override
    protected void doRollback(TaskStepContext context) {
        String version = (String) context.getRollbackData().get("version");
        if (version != null) {
            sdkInstaller.rollback(language, version, session.getProjectId());
        }
    }

    private SdkVersion resolveVersion(String version) {
        return sdkVersionRepository.findByLanguageAndOsAndArch(
                        language, OsTypeEnum.WINDOWS, ArchEnum.AMD64).stream()
                .filter(v -> v.getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new TerraScoutException(TerraScoutError.NO_SDK_VERSION_MATCH,
                        "无可用下载元数据: " + language + " " + version));
    }

    private static String homeKey(LanguageEnum language) {
        switch (language) {
            case JAVA:
                return "java";
            case NODE:
                return "node";
            case GO:
                return "go";
            case PYTHON:
                return "python";
            default:
                return language.name().toLowerCase(Locale.ROOT);
        }
    }
}
