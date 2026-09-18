package com.terrascout.orchestrator.app.step;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.task.TaskStepContext;
import com.terrascout.orchestrator.task.TaskStepDefinition;

/**
 * CREATE_ISOLATION 步骤：创建隔离域 {@code .devenv}（m2 / npm-cache / 归属标记）。
 *
 * <p>安全：隔离域已存在但非本工具创建（无标记且非空）→ 409004 拒绝覆盖。
 */
public class CreateIsolationStep extends AbstractStepExecutor {

    private static final String MARKER = ".terra-scout";

    public CreateIsolationStep(TaskJournal journal, AssemblySession session,
                               TaskStepDefinition definition, int stepIndex) {
        super(journal, session, definition, stepIndex);
    }

    @Override
    protected StepStatusEnum doExecute(TaskStepContext context) {
        Path root = session.getProjectRoot();
        Path isolation = PathConstants.isolationRoot(root);
        Path marker = isolation.resolve(MARKER);
        if (Files.exists(isolation) && !Files.exists(marker) && isNonEmpty(isolation)) {
            throw new TerraScoutException(TerraScoutError.ISOLATION_DIR_CONFLICT,
                    "隔离域已存在且非本工具创建: " + isolation);
        }
        try {
            Files.createDirectories(PathConstants.isolationM2(root));
            Files.createDirectories(PathConstants.isolationNpmCache(root));
            Files.writeString(marker, "terra-scout", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.ISOLATION_CONFIG_FAILED,
                    "隔离域目录创建失败: " + isolation, e);
        }
        session.setIsolationDir(isolation);
        context.getOutput().put("isolationDir", isolation.toString());
        context.getRollbackData().put("isolationDir", isolation.toString());
        return StepStatusEnum.SUCCESS;
    }

    @Override
    protected void doRollback(TaskStepContext context) {
        String dir = (String) context.getRollbackData().get("isolationDir");
        if (dir != null) {
            deleteRecursively(Path.of(dir));
        }
    }

    private static boolean isNonEmpty(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return true;
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.ROLLBACK_FAILED,
                    "回滚删除隔离域失败: " + root, e);
        }
    }
}
