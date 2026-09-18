package com.terrascout.orchestrator.app.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.env.ProcessExecutor;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

/**
 * VERIFY_PROJECT 步骤：以注入环境执行验证命令（mvn -v / node -v / go version / python --version），
 * 非零退出 → 422015。
 */
public class VerifyProjectStep extends AbstractStepExecutor {

    private final ProcessExecutor processExecutor;

    public VerifyProjectStep(TaskJournal journal, AssemblySession session,
                             TaskStepDefinition definition, int stepIndex,
                             ProcessExecutor processExecutor) {
        super(journal, session, definition, stepIndex);
        this.processExecutor = processExecutor;
    }

    @Override
    protected StepStatusEnum doExecute(TaskStepContext context) {
        List<CommandSpec> commands = verifyCommands();
        if (session.getPlan() != null) {
            session.getPlan().setVerifyCommands(commands);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (CommandSpec command : commands) {
            ProcessExecutor.Result result = runCommand(processExecutor, command);
            if (result.exitCode() != 0) {
                throw new TerraScoutException(TerraScoutError.COMMAND_EXECUTION_FAILED,
                        command.getCommand() + " 验证失败(exit=" + result.exitCode() + ")");
            }
            results.add(Map.of("command", command.getCommand(), "exitCode", result.exitCode()));
        }
        context.getOutput().put("verify", results);
        return StepStatusEnum.SUCCESS;
    }

    private List<CommandSpec> verifyCommands() {
        List<CommandSpec> commands = new ArrayList<>();
        ProjectTypeEnum type = session.getProjectType();
        if (type == ProjectTypeEnum.MAVEN || type == ProjectTypeEnum.MIXED) {
            commands.add(command("mvn", List.of("-v")));
        }
        if (type == ProjectTypeEnum.NPM || type == ProjectTypeEnum.MIXED) {
            commands.add(command("node", List.of("-v")));
        }
        if (type == ProjectTypeEnum.GO || type == ProjectTypeEnum.MIXED) {
            commands.add(command("go", List.of("version")));
        }
        if (type == ProjectTypeEnum.PYTHON || type == ProjectTypeEnum.MIXED) {
            commands.add(command("python", List.of("--version")));
        }
        return commands;
    }

    private static CommandSpec command(String name, List<String> args) {
        CommandSpec spec = new CommandSpec();
        spec.setCommand(name);
        spec.setArgs(args);
        return spec;
    }
}
