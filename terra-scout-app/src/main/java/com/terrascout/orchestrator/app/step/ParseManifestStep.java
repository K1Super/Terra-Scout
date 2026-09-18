package com.terrascout.orchestrator.app.step;

import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.parser.ConstraintExtractor;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

/**
 * PARSE_MANIFEST 步骤：解析声明文件产出版本约束列表，写入会话供 MATCH_VERSION 使用。
 */
public class ParseManifestStep extends AbstractStepExecutor {

    private final ConstraintExtractor constraintExtractor;

    public ParseManifestStep(TaskJournal journal, AssemblySession session,
                             TaskStepDefinition definition, int stepIndex,
                             ConstraintExtractor constraintExtractor) {
        super(journal, session, definition, stepIndex);
        this.constraintExtractor = constraintExtractor;
    }

    @Override
    protected StepStatusEnum doExecute(TaskStepContext context) {
        session.setConstraints(constraintExtractor.extract(session.getProjectRoot()));
        context.getOutput().put("constraints", session.getConstraints());
        return StepStatusEnum.SUCCESS;
    }
}
