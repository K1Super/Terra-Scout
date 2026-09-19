package com.terrascout.orchestrator.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 步骤上下文测试。
 */
class TaskStepContextTest {

    @Test
    void settersChainAndGet() {
        TaskStepContext ctx = new TaskStepContext()
                .setTaskId("t1")
                .setProjectId("p1")
                .setProjectRoot(Path.of("C:\\proj"))
                .setIsolationDir(Path.of("C:\\proj\\.devenv"));
        assertThat(ctx.getTaskId()).isEqualTo("t1");
        assertThat(ctx.getProjectId()).isEqualTo("p1");
        assertThat(ctx.getProjectRoot()).isEqualTo(Path.of("C:\\proj"));
        assertThat(ctx.getIsolationDir()).isEqualTo(Path.of("C:\\proj\\.devenv"));
    }

    @Test
    void unsetFieldsAreNullByDefault() {
        TaskStepContext ctx = new TaskStepContext();
        assertThat(ctx.getTaskId()).isNull();
        assertThat(ctx.getProjectId()).isNull();
        assertThat(ctx.getProjectRoot()).isNull();
        assertThat(ctx.getIsolationDir()).isNull();
    }

    @Test
    void mapsAreMutableAndMutuallyDistinct() {
        TaskStepContext ctx = new TaskStepContext();
        ctx.getInput().put("k", "v");
        ctx.getOutput().put("k", "out");
        ctx.getRollbackData().put("k", "rb");
        assertThat(ctx.getInput()).containsEntry("k", "v");
        assertThat(ctx.getOutput()).containsEntry("k", "out");
        assertThat(ctx.getRollbackData()).containsEntry("k", "rb");
        assertThat(ctx.getInput()).isNotSameAs(ctx.getOutput());
        assertThat(ctx.getOutput()).isNotSameAs(ctx.getRollbackData());
    }

    @Test
    void newContextHasEmptyMaps() {
        TaskStepContext ctx = new TaskStepContext();
        assertThat(ctx.getInput()).isEmpty();
        assertThat(ctx.getOutput()).isEmpty();
        assertThat(ctx.getRollbackData()).isEmpty();
    }
}
