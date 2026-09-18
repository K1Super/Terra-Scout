package com.terrascout.orchestrator.app.step;

import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.parser.ProjectTypeDetector;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

/**
 * DETECT_PROJECT 步骤：从磁盘识别项目类型（安全兜底，防止分析后声明文件被删改）。
 */
public class DetectProjectStep extends AbstractStepExecutor {

    public DetectProjectStep(TaskJournal journal, AssemblySession session,
                             TaskStepDefinition definition, int stepIndex) {
        super(journal, session, definition, stepIndex);
    }

    @Override
    protected StepStatusEnum doExecute(TaskStepContext context) {
        ProjectTypeEnum type = ProjectTypeDetector.detect(session.getProjectRoot());
        if (type == ProjectTypeEnum.UNKNOWN) {
            throw new TerraScoutException(TerraScoutError.PROJECT_TYPE_UNKNOWN,
                    "无法识别项目类型: " + session.getProjectRoot());
        }
        session.setProjectType(type);
        context.getOutput().put("projectType", type.name());
        context.getOutput().put("projectRoot", session.getProjectRoot().toString());
        return StepStatusEnum.SUCCESS;
    }
}
