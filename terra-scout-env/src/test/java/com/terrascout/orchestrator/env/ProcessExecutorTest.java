package com.terrascout.orchestrator.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 命令执行器测试：命令注入拦截于 spawn 前。
 */
class ProcessExecutorTest {

    @TempDir
    java.nio.file.Path tmp;

    private static CommandSpec spec(String cmd, String... args) {
        CommandSpec s = new CommandSpec();
        s.setCommand(cmd);
        s.setArgs(List.of(args));
        return s;
    }

    @Test
    void buildOsCommandMapping() {
        assertThat(ProcessExecutor.buildOsCommand("mvn.cmd", "mvn", List.of("-version")))
                .containsExactly("cmd.exe", "/c", "mvn.cmd", "-version");
        assertThat(ProcessExecutor.buildOsCommand("npm.cmd", "npm", List.of("install")))
                .containsExactly("cmd.exe", "/c", "npm.cmd", "install", "--ignore-scripts");
        // 已显式提供 --ignore-scripts 时不重复追加
        assertThat(ProcessExecutor.buildOsCommand("npm.cmd", "npm", List.of("--ignore-scripts")))
                .doesNotHaveDuplicates();
        assertThat(ProcessExecutor.buildOsCommand("java.exe", "java", List.of("-version")))
                .containsExactly("java.exe", "-version");
        assertThat(ProcessExecutor.buildOsCommand("node.exe", "node", List.of("--version")))
                .containsExactly("node.exe", "--version");
    }

    @Test
    void nullArgsProduceCommandOnly() {
        assertThat(ProcessExecutor.buildOsCommand("java.exe", "java", null))
                .containsExactly("java.exe");
    }

    @Test
    void spawnSuccessJavaVersionReturnsExitZero() {
        try (ProcessExecutor executor = new ProcessExecutor()) {
            ProcessExecutor.Result r = executor.execute(spec("java", "-version"), tmp, null);
            assertThat(r.exitCode()).isZero();
            assertThat(r.stderr()).contains("version");
        }
    }

    @Test
    void injectionRejectedBeforeSpawn() {
        AtomicBoolean spawned = new AtomicBoolean(false);
        ProcessExecutor executor = new ProcessExecutor(1_000, pb -> {
            spawned.set(true);
            throw new AssertionError("不得触发 spawn");
        });
        // 参数含注入元字符 → 静默 validate 阶段抛 403002
        assertThatThrownBy(() -> executor.execute(spec("mvn", "-version; rm -rf /"), tmp, null))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
        assertThat(spawned).isFalse();
    }

    @Test
    void probeSurfaceCommandRejectedOnExecuteButAcceptedOnExecuteProbe() {
        // go/pip/python 已放行装配面（Go/Python 全链路装配）；仅 py（Python Launcher）仍探测面专属
        AtomicBoolean spawned = new AtomicBoolean(false);
        ProcessExecutor executor = new ProcessExecutor(1_000, pb -> {
            spawned.set(true);
            throw new java.io.IOException("FakeSpawnFailure");
        });
        assertThatThrownBy(() -> executor.execute(spec("py", "-0p"), tmp, null))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
        assertThat(spawned).isFalse();
        assertThatThrownBy(() -> executor.executeProbe(spec("py", "-0p"), tmp, null))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_EXECUTION_FAILED));
        assertThat(spawned).isTrue();
    }

    @Test
    void executeProbeStillRejectsUnknownCommand() {
        AtomicBoolean spawned = new AtomicBoolean(false);
        ProcessExecutor executor = new ProcessExecutor(1_000, pb -> {
            spawned.set(true);
            throw new AssertionError("不得触发 spawn");
        });
        // mvn 不在探测面
        assertThatThrownBy(() -> executor.executeProbe(spec("mvn", "-version"), tmp, null))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
        assertThat(spawned).isFalse();
    }

    @Test
    void executeProbeAcceptsAbsolutePathExecutable() {
        // 绝对路径探测命令须通过白名单并进入 spawn(假 spawn 抛 IOException → 422015 证明已越过校验)
        AtomicBoolean spawned = new AtomicBoolean(false);
        ProcessExecutor executor = new ProcessExecutor(1_000, pb -> {
            spawned.set(true);
            throw new java.io.IOException("FakeSpawnFailure");
        });
        assertThatThrownBy(() -> executor.executeProbe(spec("C:\\Windows\\py.exe", "-0p"), tmp, null))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_EXECUTION_FAILED));
        assertThat(spawned).isTrue();
    }

    @Test
    void executeProbeRejectsUnknownAbsolutePathBeforeSpawn() {
        // 尾段文件名不在白名单的绝对路径在 spawn 前被 403002 拦截
        AtomicBoolean spawned = new AtomicBoolean(false);
        ProcessExecutor executor = new ProcessExecutor(1_000, pb -> {
            spawned.set(true);
            throw new AssertionError("不得触发 spawn");
        });
        assertThatThrownBy(() -> executor.executeProbe(
                spec("C:\\Windows\\System32\\calc.exe"), tmp, null))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
        assertThat(spawned).isFalse();
    }

    @Test
    void executeProbeExplicitTimeoutKillsHungCommand() {
        // 探测面显式超时覆盖实例默认值——注入确实慢的外部进程,100ms 内必须被销毁并抛 422015
        ProcessExecutor executor = new ProcessExecutor(60_000, pb -> {
            ProcessBuilder slow = new ProcessBuilder("cmd.exe", "/c", "ping", "-n", "10", "127.0.0.1");
            return slow.start();
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> executor.executeProbe(
                spec("java", "-XshowSettings:properties", "-version"), tmp, null, 100))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_EXECUTION_FAILED));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(10_000L); // 远小于慢进程时长,证明确实被超时截断
    }

    @Test
    void executeProbeRejectsInvalidExplicitTimeout() {
        try (ProcessExecutor executor = new ProcessExecutor()) {
            assertThatThrownBy(() -> executor.executeProbe(spec("java", "-version"), tmp, null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> executor.executeProbe(spec("java", "-version"), tmp, null, -5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void timeoutKillsAndThrows422015() {
        ProcessExecutor executor = new ProcessExecutor(100, pb -> {
            // 注入一个确实慢的外部进程，确定性触发超时路径（Windows 目标环境）
            ProcessBuilder slow = new ProcessBuilder("cmd.exe", "/c", "ping", "-n", "5", "127.0.0.1");
            return slow.start();
        });
        assertThatThrownBy(() -> executor.execute(spec("java", "-version"), tmp, null))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_EXECUTION_FAILED));
    }

    @Test
    void invalidTimeoutRejected() {
        assertThatThrownBy(() -> new ProcessExecutor(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullSpecRejected() {
        try (ProcessExecutor executor = new ProcessExecutor()) {
            assertThatThrownBy(() -> executor.execute(null, tmp, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void resolveExeOnInjectedPathPinsToInjectedPathFirstHit() throws Exception {
        // Windows 裸名按父进程 PATH 解析（JDK 行为），注入 PATH 不生效；
        // 必须在 spawn 前钉到注入 PATH 首项对应目录，杜绝系统同名工具错配
        java.nio.file.Path sdkBin = java.nio.file.Files.createDirectories(tmp.resolve("go").resolve("bin"));
        java.nio.file.Files.writeString(sdkBin.resolve("go.exe"), "fake");
        String path = sdkBin + System.getProperty("path.separator", ";")
                + tmp.resolve("system-dir").toString();
        String resolved = ProcessExecutor.resolveExeOnInjectedPath("go.exe",
                Map.of(EnvInjector.PATH_VAR, path));
        assertThat(resolved).isEqualTo(sdkBin.resolve("go.exe").toString());
    }

    @Test
    void resolveExeOnInjectedPathFallsBackWhenNoEnvOrMissOrPathForm() {
        assertThat(ProcessExecutor.resolveExeOnInjectedPath("go.exe", null)).isEqualTo("go.exe");
        assertThat(ProcessExecutor.resolveExeOnInjectedPath("go.exe", Map.of())).isEqualTo("go.exe");
        // PATH 各目录均无此文件 → 保持裸名（回退父进程 PATH 既有语义）
        assertThat(ProcessExecutor.resolveExeOnInjectedPath("go.exe",
                Map.of(EnvInjector.PATH_VAR, tmp.resolve("nope").toString())))
                .isEqualTo("go.exe");
        // 已携带路径分隔符（绝对/相对路径）→ 原样返回，不做二次定位
        assertThat(ProcessExecutor.resolveExeOnInjectedPath("C:\\tools\\go.exe",
                Map.of(EnvInjector.PATH_VAR, tmp.toString())))
                .isEqualTo("C:\\tools\\go.exe");
    }

    @Test
    void executePinsBareExeToInjectedPathBeforeSpawn() throws Exception {
        // 装配面 spawn 前必须把裸文件名解析为注入 PATH 下的绝对路径
        java.nio.file.Path sdkBin = java.nio.file.Files.createDirectories(tmp.resolve("sdk").resolve("bin"));
        java.nio.file.Files.writeString(sdkBin.resolve("java.exe"), "fake");
        AtomicReference<List<String>> spawned = new AtomicReference<>();
        ProcessExecutor executor = new ProcessExecutor(1_000, pb -> {
            spawned.set(pb.command());
            throw new java.io.IOException("FakeSpawnFailure");
        });
        Map<String, String> env = Map.of(EnvInjector.PATH_VAR, sdkBin.toString());
        assertThatThrownBy(() -> executor.execute(spec("java", "-version"), tmp, env))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_EXECUTION_FAILED));
        assertThat(spawned.get()).first().isEqualTo(sdkBin.resolve("java.exe").toString());
    }
}
