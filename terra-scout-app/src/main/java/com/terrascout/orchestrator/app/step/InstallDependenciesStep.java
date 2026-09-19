package com.terrascout.orchestrator.app.step;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.dto.DependencyInstallItem;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.env.EnvInjector;
import com.terrascout.orchestrator.env.ProcessExecutor;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

/**
 * INSTALL_DEPENDENCIES 步骤：按项目类型在隔离域安装依赖
 * （mvn install / npm install / go mod download / python -m pip install -r requirements.txt）。
 *
 * <p>先以 {@link EnvInjector} 计算注入环境并存入会话，供后续 BIND_ENV 复用；非零退出 → 422012。
 */
public class InstallDependenciesStep extends AbstractStepExecutor {

    private final ProcessExecutor processExecutor;
    private final EnvInjector envInjector;

    public InstallDependenciesStep(TaskJournal journal, AssemblySession session,
                                   TaskStepDefinition definition, int stepIndex,
                                   ProcessExecutor processExecutor, EnvInjector envInjector) {
        super(journal, session, definition, stepIndex);
        this.processExecutor = processExecutor;
        this.envInjector = envInjector;
    }

    @Override
    protected StepStatusEnum doExecute(TaskStepContext context) {
        session.setEnv(envInjector.buildEnv(session.getProjectRoot(), session.getSdkHomes()));
        List<DependencyInstallItem> items = dependencyItems();
        if (session.getPlan() != null) {
            session.getPlan().setDependencyInstalls(items);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (DependencyInstallItem item : items) {
            ProcessExecutor.Result result = runCommand(processExecutor, item.getInstallCommand());
            if (result.exitCode() != 0) {
                throw new TerraScoutException(TerraScoutError.DEPENDENCY_INSTALL_FAILED,
                        item.getEcosystem() + " 依赖安装失败(exit=" + result.exitCode() + ")");
            }
            results.add(Map.of("ecosystem", item.getEcosystem(), "exitCode", result.exitCode()));
        }
        context.getOutput().put("dependencies", results);
        return StepStatusEnum.SUCCESS;
    }

    private List<DependencyInstallItem> dependencyItems() {
        List<DependencyInstallItem> items = new ArrayList<>();
        ProjectTypeEnum type = session.getProjectType();
        if (type == ProjectTypeEnum.MAVEN || type == ProjectTypeEnum.MIXED) {
            items.add(mavenItem());
        }
        if (type == ProjectTypeEnum.NPM || type == ProjectTypeEnum.MIXED) {
            items.add(npmItem());
        }
        if ((type == ProjectTypeEnum.GO || type == ProjectTypeEnum.MIXED)
                && hasManifest("go.mod")) {
            items.add(goItem());
        }
        // Python 依赖仅当存在 requirements.txt 时安装；无依赖文件则无依赖项（SKIPPED 语义，不失败）
        if (hasManifest("requirements.txt")) {
            items.add(pythonItem());
        }
        return items;
    }

    private boolean hasManifest(String fileName) {
        return Files.isRegularFile(session.getProjectRoot().resolve(fileName));
    }

    private static DependencyInstallItem mavenItem() {
        DependencyInstallItem item = new DependencyInstallItem();
        item.setEcosystem(DependencyInstallItem.ECOSYSTEM_MAVEN);
        item.setIsolation(".devenv/m2");
        item.setInstallCommand(command("mvn", List.of("-B", "-DskipTests", "install")));
        return item;
    }

    private static DependencyInstallItem npmItem() {
        DependencyInstallItem item = new DependencyInstallItem();
        item.setEcosystem(DependencyInstallItem.ECOSYSTEM_NPM);
        item.setIsolation(".devenv/npm-cache");
        item.setInstallCommand(command("npm", List.of("install")));
        return item;
    }

    private static DependencyInstallItem goItem() {
        DependencyInstallItem item = new DependencyInstallItem();
        item.setEcosystem(DependencyInstallItem.ECOSYSTEM_GO);
        item.setIsolation(".devenv/go-cache");
        item.setInstallCommand(command("go", List.of("mod", "download")));
        return item;
    }

    private static DependencyInstallItem pythonItem() {
        DependencyInstallItem item = new DependencyInstallItem();
        item.setEcosystem(DependencyInstallItem.ECOSYSTEM_PYTHON);
        // 通过所选解释器自身执行 pip（python -m pip）：Windows 官方 zip 分发包不含
        // Scripts\pip.exe 入口，直接用 pip 会错解析到系统解释器并污染系统环境；
        // 依赖装入所选 SDK 的 Lib\site-packages（项目专属 SDK 隔离），无需额外隔离目录
        item.setInstallCommand(command("python", List.of("-m", "pip", "install", "-r",
                "requirements.txt")));
        return item;
    }

    private static CommandSpec command(String name, List<String> args) {
        CommandSpec spec = new CommandSpec();
        spec.setCommand(name);
        spec.setArgs(args);
        return spec;
    }
}
