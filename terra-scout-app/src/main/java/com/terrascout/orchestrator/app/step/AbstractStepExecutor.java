package com.terrascout.orchestrator.app.step;

import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.enums.CommandStatusEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.env.ProcessExecutor;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;
import com.terrascout.orchestrator.task.TaskStepExecutor;

/**
 * 步骤执行器模板：统一承接引擎调度（execute / rollback）、步骤日志落库与异常 → FAILED 转换。
 *
 * <p>业务子类仅实现 {@link #doExecute(TaskStepContext)}；失败抛 {@link TerraScoutException}，
 * 由本模板捕获并写入 {@link AssemblySession#fail(int, String)}（编排结束后回写 task.error_code），
 * 再返回 FAILED 交引擎驱动重试 / 回滚。
 */
public abstract class AbstractStepExecutor implements TaskStepExecutor {

    protected final TaskJournal journal;
    protected final AssemblySession session;
    private final TaskStepDefinition definition;
    private final int stepIndex;

    protected AbstractStepExecutor(TaskJournal journal, AssemblySession session,
                                   TaskStepDefinition definition, int stepIndex) {
        this.journal = journal;
        this.session = session;
        this.definition = definition;
        this.stepIndex = stepIndex;
    }

    @Override
    public StepStatusEnum execute(TaskStepContext context) {
        journal.stepStarted(session.getTaskId(), stepIndex, definition.name());
        try {
            StepStatusEnum status = doExecute(context);
            journal.stepFinished(session.getTaskId(), stepIndex, status, context.getOutput());
            return status;
        } catch (TerraScoutException e) {
            session.fail(e.getError().getCode(), e.getMessage());
            journal.stepFailed(session.getTaskId(), stepIndex, e.getError().getCode(), e.getMessage());
            return StepStatusEnum.FAILED;
        } catch (RuntimeException e) {
            session.fail(TerraScoutError.UNKNOWN.getCode(), e.getMessage());
            journal.stepFailed(session.getTaskId(), stepIndex,
                    TerraScoutError.UNKNOWN.getCode(), e.getMessage());
            return StepStatusEnum.FAILED;
        }
    }

    @Override
    public void rollback(TaskStepContext context) {
        doRollback(context);
        journal.stepRolledBack(session.getTaskId(), stepIndex);
    }

    /** 执行命令并落 command_execution 审计；启动失败/超时记为 TIMEOUT 并重抛。 */
    protected ProcessExecutor.Result runCommand(ProcessExecutor executor, CommandSpec spec) {
        long startedAt = System.currentTimeMillis();
        ProcessExecutor.Result result;
        try {
            result = executor.execute(spec, session.getProjectRoot(), session.getEnv());
        } catch (TerraScoutException e) {
            journal.recordCommand(session.getProjectId(), spec, session.getProjectRoot().toString(),
                    null, CommandStatusEnum.TIMEOUT, startedAt, System.currentTimeMillis());
            throw e;
        }
        CommandStatusEnum status = result.exitCode() == 0 ? CommandStatusEnum.SUCCESS : CommandStatusEnum.FAILED;
        journal.recordCommand(session.getProjectId(), spec, session.getProjectRoot().toString(),
                result.exitCode(), status, startedAt, System.currentTimeMillis());
        return result;
    }

    protected abstract StepStatusEnum doExecute(TaskStepContext context);

    /** 默认为无补偿步骤。 */
    protected void doRollback(TaskStepContext context) {
    }

    @Override
    public boolean isPausable() {
        return definition.pausable();
    }

    @Override
    public boolean isRetryable() {
        return definition.retryable();
    }

    @Override
    public int getTimeoutSeconds() {
        return definition.timeoutSeconds();
    }
}
