package com.terrascout.orchestrator.app.step;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.dto.InstallPlan;
import com.terrascout.orchestrator.core.dto.ProjectConstraint;
import com.terrascout.orchestrator.core.dto.SdkInstallItem;
import com.terrascout.orchestrator.core.dto.VersionOverride;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.InstallStatusEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

/**
 * MATCH_VERSION 步骤：按版本匹配算法为每个约束挑出推荐版本，产出 SDK 安装计划。
 *
 * <p>会话携带 {@code versionOverrides} 时逐语言覆盖自动推荐版本（校验不过 422006），
 * 且每个安装项回写候选表供前端展示/回选。
 */
public class MatchVersionStep extends AbstractStepExecutor {

    private final SdkVersionRepository sdkVersionRepository;
    private final SdkInstallRecordRepository installRecordRepository;

    public MatchVersionStep(TaskJournal journal, AssemblySession session,
                            TaskStepDefinition definition, int stepIndex,
                            SdkVersionRepository sdkVersionRepository,
                            SdkInstallRecordRepository installRecordRepository) {
        super(journal, session, definition, stepIndex);
        this.sdkVersionRepository = sdkVersionRepository;
        this.installRecordRepository = installRecordRepository;
    }

    @Override
    protected StepStatusEnum doExecute(TaskStepContext context) {
        Map<LanguageEnum, String> overrides = overrideMap();
        Map<LanguageEnum, SdkInstallItem> sdkPlan = new LinkedHashMap<>();
        List<SdkInstallItem> items = new ArrayList<>();
        for (ProjectConstraint constraint : session.getConstraints()) {
            LanguageEnum language = constraint.getLanguage();
            Set<String> installed = installedVersions(language);
            List<SdkVersion> available = sdkVersionRepository.findByLanguageAndOsAndArch(
                    language, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
            SdkInstallItem item = SdkVersionMatcher.match(
                    constraint.getConstraint(), installed, available);
            String overrideVersion = overrides.get(language);
            if (overrideVersion != null) {
                item = SdkVersionMatcher.overrideVersion(
                        item, overrideVersion, constraint.getConstraint(), installed, available);
            }
            sdkPlan.put(language, item);
            items.add(item);
        }
        session.setSdkPlan(sdkPlan);

        InstallPlan plan = new InstallPlan();
        plan.setPlanId(session.getProjectId());
        plan.setSdkInstalls(items);
        session.setPlan(plan);

        context.getOutput().put("sdkInstalls", items);
        return StepStatusEnum.SUCCESS;
    }

    /** 会话 versionOverrides 压平为语言→版本（无覆盖语言缺席；空版本视为无效 422006）。 */
    private Map<LanguageEnum, String> overrideMap() {
        Map<LanguageEnum, String> overrides = new HashMap<>();
        if (session.getVersionOverrides() == null) {
            return overrides;
        }
        for (VersionOverride override : session.getVersionOverrides()) {
            if (override == null || override.getLanguage() == null) {
                throw new TerraScoutException(TerraScoutError.NO_SDK_VERSION_MATCH,
                        "versionOverrides 缺少 language 字段");
            }
            overrides.put(override.getLanguage(), override.getVersion());
        }
        return overrides;
    }

    private Set<String> installedVersions(LanguageEnum language) {
        return installRecordRepository.findAll().stream()
                .filter(r -> r.getStatus() == InstallStatusEnum.SUCCESS && r.getLanguage() == language)
                .map(SdkInstallRecord::getVersion)
                .collect(Collectors.toSet());
    }
}
