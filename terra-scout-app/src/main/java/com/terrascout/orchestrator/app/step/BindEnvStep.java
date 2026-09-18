package com.terrascout.orchestrator.app.step;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.env.EnvScriptGenerator;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

/**
 * BIND_ENV 步骤：把会话中已计算的注入环境落成 {@code .devenv\env.ps1}（ADR-007 进程级注入入口）。
 */
public class BindEnvStep extends AbstractStepExecutor {

    private final EnvScriptGenerator envScriptGenerator;

    public BindEnvStep(TaskJournal journal, AssemblySession session,
                       TaskStepDefinition definition, int stepIndex,
                       EnvScriptGenerator envScriptGenerator) {
        super(journal, session, definition, stepIndex);
        this.envScriptGenerator = envScriptGenerator;
    }

    @Override
    protected StepStatusEnum doExecute(TaskStepContext context) {
        Path script = PathConstants.isolationEnvScript(session.getProjectRoot());
        envScriptGenerator.write(script, session.getEnv());
        context.getOutput().put("envScript", script.toString());
        context.getRollbackData().put("envScript", script.toString());
        return StepStatusEnum.SUCCESS;
    }

    @Override
    protected void doRollback(TaskStepContext context) {
        String script = (String) context.getRollbackData().get("envScript");
        if (script != null) {
            try {
                Files.deleteIfExists(Path.of(script));
            } catch (IOException e) {
                throw new IllegalStateException("回滚删除 env.ps1 失败: " + script, e);
            }
        }
    }
}
