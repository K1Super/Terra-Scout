package com.terrascout.orchestrator.app.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.env.ProcessExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 系统级 SDK 探测单测：策略链（release / javapath 执行解析 / node execPath+version /
 * py -0p / go VERSION 文件）、多版本兄弟扫描、nvm 多版本、绝对路径绑定、保序不可变、
 * 冲突埋点、PATH 全量回退、single-flight、显式超时、诊断 SPI、平台守卫、ProgramW6432、
 * NVM_HOME 权威、时钟回拨、长路径前缀。
 */
class SystemSdkProberTest {

    private final ProcessExecutor executor = mock(ProcessExecutor.class);
    private final SystemSdkProber prober = new SystemSdkProber(executor);

    @TempDir
    Path dir;

    // ── 测试基建 ─────────────────────────────────────────────────

    private Path jdkHome(String version) throws Exception {
        return jdkHome(dir, version);
    }

    private Path jdkHome(Path parent, String version) throws Exception {
        Path home = parent.resolve("jdk-" + version.replace('.', '_'));
        Files.createDirectories(home.resolve("bin"));
        Files.write(home.resolve("bin").resolve("java.exe"), new byte[] {1});
        Files.write(home.resolve("release"), ("JAVA_VERSION=\"" + version + "\"\n").getBytes());
        return home;
    }

    private ProcessExecutor.Result showSettings(String home, String version) {
        return new ProcessExecutor.Result(0,
                "    java.home = " + home + "\r\n    java.version = " + version + "\r\n", "", 5L);
    }

    private static ProbeEnv fakeEnv(Map<String, String> vars, String osName) {
        return new ProbeEnv() {
            @Override
            public String get(String key) {
                return vars.get(key);
            }

            @Override
            public String getProperty(String key) {
                return "os.name".equals(key) ? osName : null;
            }
        };
    }

    /** 收集型诊断（验收）：探测失败的每条降级都必须有事件。 */
    private static final class RecordingDiagnostics implements ProbeDiagnostics {
        final List<String> failures = new ArrayList<>();
        final List<String> successes = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();
        final List<String> scansSkipped = new ArrayList<>();

        @Override
        public void recordProbeFailure(String source, String command, Throwable cause) {
            failures.add(source + "|" + command + "|"
                    + (cause == null ? "null" : cause.getClass().getSimpleName()));
        }

        @Override
        public void recordProbeSuccess(String source, String command, long elapsedMs) {
            successes.add(source + "|" + command);
        }

        @Override
        public void recordDuplicateVersion(String source, String version, String kept, String discarded) {
            duplicates.add(source + "|" + version + "|" + kept + "|" + discarded);
        }

        @Override
        public void recordScanSkipped(String source, Path root, Throwable cause) {
            scansSkipped.add(source + "|" + root);
        }
    }

    // ── JAVA_HOME 直读 ───────────────────────────────────────────

    @Test
    void javaHomeDetected() throws Exception {
        Path home = jdkHome("17.0.9");
        assertThat(prober.probeJava(home.toString(), List.of()))
                .containsEntry("17.0.9", home.toString());
    }

    @Test
    void javaHomeBlankOrNullIgnored() {
        assertThat(prober.probeJava("  ", List.of())).isEmpty();
        assertThat(prober.probeJava(null, List.of())).isEmpty();
    }

    @Test
    void javaHomeWithLongPathPrefixNormalized() throws Exception {
        // 剥 \\?\ 前缀后解析;路径本身无 release,结果应为空(旧实现直接丢弃,行为一致)
        assertThat(prober.probeJava("\\\\?\\C:\\tools\\jdk", List.of())).isEmpty();
    }

    @Test
    void javaHomeWithInvalidCharsIgnored() {
        assertThat(prober.probeJava("C:\\tem\u0000p", List.of())).isEmpty();
    }

    @Test
    void javaHomeLackingBinOrReleaseSkipped() throws Exception {
        Path noRelease = dir.resolve("no-release");
        Files.createDirectories(noRelease.resolve("bin"));
        Files.write(noRelease.resolve("bin").resolve("java.exe"), new byte[] {1});
        assertThat(prober.probeJava(noRelease.toString(), List.of())).isEmpty();

        Path noBinary = dir.resolve("no-bin");
        Files.createDirectories(noBinary);
        assertThat(prober.probeJava(noBinary.toString(), List.of())).isEmpty();
    }

    // ── PATH 中的 java.exe ───────────────────────────────────────

    @Test
    void javaFromPathVariableViaRelease() throws Exception {
        Path home = jdkHome("21.0.2");
        assertThat(prober.probeJava(null, List.of(home.resolve("bin"))))
                .containsEntry("21.0.2", home.toString());
    }

    @Test
    void releaseVersionStripsBuildSuffix() throws Exception {
        Path home = dir.resolve("jdk-build");
        Files.createDirectories(home.resolve("bin"));
        Files.write(home.resolve("bin").resolve("java.exe"), new byte[] {1});
        Files.write(home.resolve("release"), "JAVA_VERSION=\"17.0.11+9\"\n".getBytes());
        assertThat(prober.probeJava(null, List.of(home.resolve("bin"))))
                .containsEntry("17.0.11", home.toString());
    }

    @Test
    void javaShimResolvedViaShowSettings() throws Exception {
        // Oracle javapath stub:目录内只有 java.exe,父目录无 release
        Path shim = dir.resolve("javapath");
        Files.createDirectories(shim);
        Files.write(shim.resolve("java.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(showSettings("D:\\dev\\jdk-17.0.9", "17.0.9+7"));

        assertThat(prober.probeJava(null, List.of(shim)))
                .containsEntry("17.0.9", "D:\\dev\\jdk-17.0.9");
    }

    @Test
    void javaShimFallsBackToVersionBanner() throws Exception {
        Path shim = dir.resolve("javapath");
        Files.createDirectories(shim);
        Files.write(shim.resolve("java.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0, "    java.home = D:\\dev\\jdk-21\n",
                        "openjdk version \"21.0.2\" 2024-01-16\n", 5L));

        assertThat(prober.probeJava(null, List.of(shim)))
                .containsEntry("21.0.2", "D:\\dev\\jdk-21");
    }

    @Test
    void javaShimShowSettingsOutputOnStderrOnly() throws Exception {
        // 回归:实测 java -XshowSettings:properties -version 的全部输出在 stderr(stdout 为空),
        // 合并两流解析后 java.home/java.version 必须仍能命中
        Path shim = dir.resolve("javapath");
        Files.createDirectories(shim);
        Files.write(shim.resolve("java.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0, "",
                        "    java.home = D:\\Deps\\jdk-17.0.12\r\n    java.version = 17.0.12\r\n", 5L));

        assertThat(prober.probeJava(null, List.of(shim)))
                .containsEntry("17.0.12", "D:\\Deps\\jdk-17.0.12");
    }

    @Test
    void javaShimExecutionFailureSilentlySkipped() throws Exception {
        Path shim = dir.resolve("javapath");
        Files.createDirectories(shim);
        Files.write(shim.resolve("java.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("进程失败"));

        assertThat(prober.probeJava(null, List.of(shim))).isEmpty();
    }

    @Test
    void javaShimExecutionFailureRecordsDiagnostics() throws Exception {
        // 降级必须可观测,不得静默消失
        Path shim = dir.resolve("javapath");
        Files.createDirectories(shim);
        Files.write(shim.resolve("java.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("进程失败"));
        RecordingDiagnostics diag = new RecordingDiagnostics();
        SystemSdkProber observed = new SystemSdkProber(executor, 30_000L, () -> 1_000L,
                diag, PathReader.DEFAULT, ProbeEnv.system());

        assertThat(observed.probeJava(null, List.of(shim))).isEmpty();
        assertThat(diag.failures).anyMatch(f -> f.contains("-XshowSettings"));
    }

    @Test
    void duplicateJavaHomeDeduplicated() throws Exception {
        Path home = jdkHome("17.0.9");
        assertThat(prober.probeJava(home.toString(), List.of(home.resolve("bin"))).values()).hasSize(1);
    }

    // ── JAVA ──────────────────────────────────────────────

    @Test
    void javaSiblingRootDiscoversAdditionalJdk() throws Exception {
        // 回归:同一管理目录并列多版本(如 D:\Deps\jdk-11.0.19 与 jdk-17.0.12),仅其一在 PATH
        Path jdk11 = jdkHome("11.0.19");
        Path jdk17 = jdkHome("17.0.12");

        Map<String, String> result = prober.probeJava(null, List.of(jdk17.resolve("bin")));
        assertThat(result)
                .containsEntry("17.0.12", jdk17.toString())
                .containsEntry("11.0.19", jdk11.toString());
    }

    @Test
    void javaDiscoveryOrderFollowsPriorityChain() throws Exception {
        // JAVA_HOME 优先于 PATH 顺序,返回 LinkedHashMap 必须保序
        Path homeA = jdkHome("11.0.19");
        Path homeB = jdkHome(dir.resolve("elsewhere"), "17.0.12");

        Map<String, String> java = prober.probeJava(homeA.toString(), List.of(homeB.resolve("bin")));
        assertThat(java.keySet()).containsExactly("11.0.19", "17.0.12");
    }

    @Test
    void duplicateVersionKeepsFirstAndRecordsConflict() throws Exception {
        // 同版本不同 home,保留先发现者并触发 duplicate 事件
        Path homeA = jdkHome("17.0.12");
        Path homeB = jdkHome(dir.resolve("elsewhere"), "17.0.12");
        RecordingDiagnostics diag = new RecordingDiagnostics();
        SystemSdkProber observed = new SystemSdkProber(executor, 30_000L, () -> 1_000L,
                diag, PathReader.DEFAULT, ProbeEnv.system());

        Map<String, String> java = observed.probeJava(homeA.toString(), List.of(homeB.resolve("bin")));
        assertThat(java.get("17.0.12")).isEqualTo(homeA.toString());
        assertThat(diag.duplicates).hasSize(1);
        assertThat(diag.duplicates.get(0)).contains("17.0.12").contains(homeA.toString());
    }

    // ── NODE ─────────────────────────────────────────────────────

    @Test
    void nodeResolvedViaExecPathAndVersion() throws Exception {
        Path symlinkDir = dir.resolve("nvm-symlink");
        Files.createDirectories(symlinkDir);
        Files.write(symlinkDir.resolve("node.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0,
                                "C:\\Users\\x\\AppData\\Roaming\\nvm\\v18.20.0\\node.exe\n", "", 5L),
                        new ProcessExecutor.Result(0, "v18.20.0\n", "", 5L));

        // home = process.execPath 的父目录(nvm 版本目录),而非软链目录
        assertThat(prober.probeNode(List.of(symlinkDir), null))
                .containsEntry("18.20.0", "C:\\Users\\x\\AppData\\Roaming\\nvm\\v18.20.0");
    }

    @Test
    void nodeExecPathFailureFallsBackToExeDir() throws Exception {
        Path home = dir.resolve("nodejs");
        Files.createDirectories(home);
        Files.write(home.resolve("node.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("进程失败"))
                .thenReturn(new ProcessExecutor.Result(0, "20.11.0\n", "", 5L));

        assertThat(prober.probeNode(List.of(home), null)).containsEntry("20.11.0", home.toString());
    }

    @Test
    void nodeVersionFailureSkipped() throws Exception {
        Path home = dir.resolve("nodejs");
        Files.createDirectories(home);
        Files.write(home.resolve("node.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0, home + "\\node.exe\n", "", 5L),
                        new ProcessExecutor.Result(1, "", "err", 5L));

        assertThat(prober.probeNode(List.of(home), null)).isEmpty();
    }

    @Test
    void nodeProbeBindsAbsoluteExecutablePerPathVariable() throws Exception {
        // 验收:PATH 中 3 个 node.exe,探测命令必须分别锚定 3 个绝对路径,禁止裸命令名
        Path a = Files.createDirectories(dir.resolve("node-a"));
        Files.write(a.resolve("node.exe"), new byte[] {1});
        Path b = Files.createDirectories(dir.resolve("node-b"));
        Files.write(b.resolve("node.exe"), new byte[] {1});
        Path c = Files.createDirectories(dir.resolve("node-c"));
        Files.write(c.resolve("node.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0, "x\\node.exe\n", "", 5L),
                        new ProcessExecutor.Result(0, "v20.11.0\n", "", 5L));

        prober.probeNode(List.of(a, b, c), null);

        ArgumentCaptor<CommandSpec> captor = ArgumentCaptor.forClass(CommandSpec.class);
        verify(executor, times(6)).executeProbe(captor.capture(), any(), any(), anyLong());
        assertThat(captor.getAllValues().stream().map(CommandSpec::getCommand).distinct())
                .containsExactlyInAnyOrder(
                        a.resolve("node.exe").toAbsolutePath().toString(),
                        b.resolve("node.exe").toAbsolutePath().toString(),
                        c.resolve("node.exe").toAbsolutePath().toString());
    }

    @Test
    void nodeExecPathWithLongPathPrefixNormalized() throws Exception {
        // execPath 输出带 \\?\ 长路径前缀,剥前缀后 home 必须是常规形式
        Path symlinkDir = dir.resolve("nvm-symlink");
        Files.createDirectories(symlinkDir);
        Files.write(symlinkDir.resolve("node.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0,
                                "\\\\?\\C:\\NVM\\v18.20.0\\node.exe\n", "", 5L),
                        new ProcessExecutor.Result(0, "v18.20.0\n", "", 5L));

        assertThat(prober.probeNode(List.of(symlinkDir), null))
                .containsEntry("18.20.0", "C:\\NVM\\v18.20.0");
    }

    @Test
    void explicitProbeTimeoutPassedToExecutor() throws Exception {
        // 验收:每次探测向执行器传显式超时,禁止走无限挂起路径
        Path home = dir.resolve("nodejs");
        Files.createDirectories(home);
        Files.write(home.resolve("node.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0, home + "\\node.exe\n", "", 5L),
                        new ProcessExecutor.Result(0, "v20.11.0\n", "", 5L));

        prober.probeNode(List.of(home), null);

        ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
        verify(executor, times(2)).executeProbe(any(CommandSpec.class), any(), any(),
                timeoutCaptor.capture());
        assertThat(timeoutCaptor.getAllValues()).containsOnly(SystemSdkProber.PROBE_TIMEOUT_MS);
    }

    @Test
    void nodeNvmRootDiscoversVersions() throws Exception {
        // nvm 场景:NVM_HOME 下并列 vX.Y.Z 目录,目录名即版本,零进程
        Path nvm = dir.resolve("nvm");
        for (String version : List.of("18.20.0", "20.11.0", "21.0.0")) {
            Path versionDir = nvm.resolve("v" + version);
            Files.createDirectories(versionDir);
            Files.write(versionDir.resolve("node.exe"), new byte[] {1});
        }

        Map<String, String> result = prober.probeNode(List.of(), nvm);
        assertThat(result)
                .containsEntry("18.20.0", nvm.resolve("v18.20.0").toString())
                .containsEntry("20.11.0", nvm.resolve("v20.11.0").toString())
                .containsEntry("21.0.0", nvm.resolve("v21.0.0").toString());
        verifyNoInteractions(executor);
    }

    @Test
    void nodeNvmRootIgnoresNonVersionEntries() throws Exception {
        Path nvm = dir.resolve("nvm");
        Path current = nvm.resolve("current");
        Files.createDirectories(current);
        Files.write(current.resolve("node.exe"), new byte[] {1});
        Path alias = nvm.resolve("alias");
        Files.createDirectories(alias);

        assertThat(prober.probeNode(List.of(), nvm)).isEmpty();
    }

    // ── PYTHON ───────────────────────────────────────────────────

    @Test
    void pythonResolvedViaPyLauncher() throws Exception {
        // py -0p 一次列出全部解释器(带 * 为默认);版本精度 x.y;命令锚定 PATH 中的 py.exe 绝对路径
        Path pyRoot = dir.resolve("launcher");
        Files.createDirectories(pyRoot);
        Files.write(pyRoot.resolve("py.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0,
                        " -V:3.11 *        D:\\Deps\\Python311\\python.exe\n"
                                + " -V:3.12          C:\\Python312\\python.exe\n", "", 5L));

        assertThat(prober.probePython(List.of(pyRoot)))
                .containsEntry("3.11", "D:\\Deps\\Python311")
                .containsEntry("3.12", "C:\\Python312");
    }

    @Test
    void pythonLauncherAbsentEnumeratesAllPathVariableHits() throws Exception {
        // 验收:无 Python Launcher 时,PATH 中每个 python.exe 都必须被收录
        Path home39 = dir.resolve("python39");
        Files.createDirectories(home39);
        Files.write(home39.resolve("python.exe"), new byte[] {1});
        Path home311 = dir.resolve("python311");
        Files.createDirectories(home311);
        Files.write(home311.resolve("python.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0, "Python 3.9.13\n", "", 5L),
                        new ProcessExecutor.Result(0, "Python 3.11.9\n", "", 5L));

        Map<String, String> result = prober.probePython(List.of(home39, home311));
        assertThat(result)
                .containsEntry("3.9.13", home39.toString())
                .containsEntry("3.11.9", home311.toString());
    }

    @Test
    void pythonFallsBackToVersionCommandWhenNoLauncher() throws Exception {
        Path home = dir.resolve("python311");
        Files.createDirectories(home);
        Files.write(home.resolve("python.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0, "Python 3.11.9\n", "", 5L));

        assertThat(prober.probePython(List.of(home))).containsEntry("3.11.9", home.toString());
    }

    @Test
    void pythonLauncherFailureWithoutFallbackTargetStaysEmpty() {
        assertThat(prober.probePython(List.of())).isEmpty();
        verifyNoInteractions(executor);
    }

    // ── GO ───────────────────────────────────────────────────────

    @Test
    void goResolvedViaVersionFile() throws Exception {
        // VERSION 文件优先(零进程):GOROOT = exe 上两级
        Path goroot = dir.resolve("Go");
        Files.createDirectories(goroot.resolve("bin"));
        Files.write(goroot.resolve("bin").resolve("go.exe"), new byte[] {1});
        Files.write(goroot.resolve("VERSION"), "go1.26.2\n".getBytes());

        assertThat(prober.probeGo(List.of(goroot.resolve("bin"))))
                .containsEntry("1.26.2", goroot.toString());
        verifyNoInteractions(executor);
    }

    @Test
    void goFallsBackToVersionCommand() throws Exception {
        Path goroot = dir.resolve("Go");
        Files.createDirectories(goroot.resolve("bin"));
        Files.write(goroot.resolve("bin").resolve("go.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenReturn(new ProcessExecutor.Result(0, "go version go1.26.2 windows/386\n", "", 5L));

        assertThat(prober.probeGo(List.of(goroot.resolve("bin"))))
                .containsEntry("1.26.2", goroot.toString());
    }

    @Test
    void goWithoutVersionFileOrCommandSkipped() throws Exception {
        Path goroot = dir.resolve("Go");
        Files.createDirectories(goroot.resolve("bin"));
        Files.write(goroot.resolve("bin").resolve("go.exe"), new byte[] {1});
        when(executor.executeProbe(any(CommandSpec.class), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("进程失败"));

        assertThat(prober.probeGo(List.of(goroot.resolve("bin")))).isEmpty();
    }

    @Test
    void goSiblingRootDiscoversAdditionalToolchain() throws Exception {
        // 兄弟扫描覆盖多 GOROOT 并列布局
        Path go126 = dir.resolve("Go126");
        Files.createDirectories(go126.resolve("bin"));
        Files.write(go126.resolve("bin").resolve("go.exe"), new byte[] {1});
        Files.write(go126.resolve("VERSION"), "go1.26.2\n".getBytes());
        Path go123 = dir.resolve("Go123");
        Files.createDirectories(go123.resolve("bin"));
        Files.write(go123.resolve("bin").resolve("go.exe"), new byte[] {1});
        Files.write(go123.resolve("VERSION"), "go1.23.4\n".getBytes());

        assertThat(prober.probeGo(List.of(go126.resolve("bin"))))
                .containsEntry("1.26.2", go126.toString())
                .containsEntry("1.23.4", go123.toString());
        verifyNoInteractions(executor);
    }

    // ── 缓存与整体契约 ───────────────────────────────────────────

    @Test
    void probeVersionsCachesWithinTtl() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SystemSdkProber cached = new SystemSdkProber(executor, 30_000L, clock::get);

        Map<LanguageEnum, Map<String, String>> first = cached.probeVersions();
        assertThat(cached.probeVersions()).isSameAs(first);
        clock.set(1_000_000L + 29_999L);
        assertThat(cached.probeVersions()).isSameAs(first);
        clock.set(1_000_000L + 30_001L);
        assertThat(cached.probeVersions()).isNotSameAs(first);
    }

    @Test
    void clockRollbackTreatsCacheAsExpiredAndRebuilds() {
        // 时钟回拨导致 elapsed 为负,必须判过期重建而非永久命中
        AtomicLong clock = new AtomicLong(1_000_000L);
        SystemSdkProber cached = new SystemSdkProber(executor, 30_000L, clock::get);

        Map<LanguageEnum, Map<String, String>> first = cached.probeVersions();
        clock.set(500_000L);
        assertThat(cached.probeVersions()).isNotSameAs(first);
    }

    @Test
    void probeVersionsAlwaysExposesAllLanguages() {
        Map<LanguageEnum, Map<String, String>> result = prober.probeVersions();
        assertThat(result.keySet()).containsExactly(
                LanguageEnum.JAVA, LanguageEnum.NODE, LanguageEnum.PYTHON, LanguageEnum.GO);
    }

    @Test
    void probeVersionsResultMapsAreOrderedAndImmutable() {
        // 验收:返回 Map 为有序不可变视图,外部写入被拒绝
        SystemSdkProber isolated = new SystemSdkProber(executor, 30_000L, () -> 1_000L,
                ProbeDiagnostics.NOOP, PathReader.DEFAULT, fakeEnv(Map.of(), "Windows 10"));
        Map<LanguageEnum, Map<String, String>> result = isolated.probeVersions();

        assertThat(result.get(LanguageEnum.JAVA)).isEmpty();
        assertThatThrownBy(() -> result.get(LanguageEnum.JAVA).put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nonWindowsPlatformReturnsEmptyAndRecordsRejection() {
        // 非 Windows 直接返回空 Map 并记录平台拒绝,不得拉起任何进程
        RecordingDiagnostics diag = new RecordingDiagnostics();
        SystemSdkProber linux = new SystemSdkProber(executor, 30_000L, () -> 1_000L,
                diag, PathReader.DEFAULT, fakeEnv(Map.of(), "Linux"));

        assertThat(linux.probeVersions()).isEmpty();
        assertThat(diag.failures).hasSize(1);
        assertThat(diag.failures.get(0)).contains("platform-guard");
        verifyNoInteractions(executor);
    }

    @Test
    void standardRootsPreferProgramW6432() throws Exception {
        // W6432 存在时 64 位根取 W6432 而非 WOW64 下的 ProgramFiles(后者被遮蔽仅作回退)
        Path pf64 = Files.createDirectories(dir.resolve("PF64"));
        Path pf32 = Files.createDirectories(dir.resolve("PF32"));
        Path pf86 = Files.createDirectories(dir.resolve("PF86"));
        jdkHome(pf64.resolve("Java"), "21.0.2");
        jdkHome(pf32.resolve("Java"), "8.0.412");
        jdkHome(pf86.resolve("Java"), "11.0.19");
        Map<String, String> withW6432 = Map.of(
                "ProgramW6432", pf64.toString(),
                "ProgramFiles", pf32.toString(),
                "ProgramFiles(x86)", pf86.toString());
        SystemSdkProber observed = new SystemSdkProber(executor, 30_000L, () -> 1_000L,
                ProbeDiagnostics.NOOP, PathReader.DEFAULT, fakeEnv(withW6432, "Windows 10"));

        Map<String, String> found = new LinkedHashMap<>();
        observed.scanStandardJavaRoots(found);
        assertThat(found).containsOnlyKeys("21.0.2", "11.0.19");

        // 回退:无 W6432 时才退回 ProgramFiles
        Map<String, String> noW6432 = Map.of(
                "ProgramFiles", pf32.toString(),
                "ProgramFiles(x86)", pf86.toString());
        SystemSdkProber fallback = new SystemSdkProber(executor, 30_000L, () -> 1_000L,
                ProbeDiagnostics.NOOP, PathReader.DEFAULT, fakeEnv(noW6432, "Windows 10"));
        Map<String, String> fallbackFound = new LinkedHashMap<>();
        fallback.scanStandardJavaRoots(fallbackFound);
        assertThat(fallbackFound).containsOnlyKeys("8.0.412", "11.0.19");
    }

    @Test
    void explicitNvmHomeOverridesAppData() throws Exception {
        // NVM_HOME 显式设置即权威,APPDATA\nvm 不得被合并
        Path nvm = dir.resolve("nvm");
        Path nvmVersion = Files.createDirectories(nvm.resolve("v18.20.0"));
        Files.write(nvmVersion.resolve("node.exe"), new byte[] {1});
        Path appDataNvm = dir.resolve("appdata").resolve("nvm");
        Path appDataVersion = Files.createDirectories(appDataNvm.resolve("v20.11.0"));
        Files.write(appDataVersion.resolve("node.exe"), new byte[] {1});
        Map<String, String> vars = Map.of(
                "NVM_HOME", nvm.toString(),
                "APPDATA", dir.resolve("appdata").toString());
        SystemSdkProber observed = new SystemSdkProber(executor, 30_000L, () -> 1_000L,
                ProbeDiagnostics.NOOP, PathReader.DEFAULT, fakeEnv(vars, "Windows 10"));

        Map<LanguageEnum, Map<String, String>> result = observed.probeVersions();
        assertThat(result.get(LanguageEnum.NODE)).containsOnlyKeys("18.20.0");
    }

    @Test
    void invalidExplicitNvmHomeIsAuthorityAndRecordsFailure() throws Exception {
        // 显式 NVM_HOME 无效即权威(不回退 APPDATA),并记录降级事件
        Path appDataNvm = dir.resolve("appdata").resolve("nvm");
        Path appDataVersion = Files.createDirectories(appDataNvm.resolve("v20.11.0"));
        Files.write(appDataVersion.resolve("node.exe"), new byte[] {1});
        RecordingDiagnostics diag = new RecordingDiagnostics();
        Map<String, String> vars = Map.of(
                "NVM_HOME", "D:\\bad\u0000path",
                "APPDATA", dir.resolve("appdata").toString());
        SystemSdkProber observed = new SystemSdkProber(executor, 30_000L, () -> 1_000L,
                diag, PathReader.DEFAULT, fakeEnv(vars, "Windows 10"));

        Map<LanguageEnum, Map<String, String>> result = observed.probeVersions();
        assertThat(result.get(LanguageEnum.NODE)).isEmpty();
        assertThat(diag.failures).anyMatch(f -> f.contains("NVM_HOME"));
    }

    @Test
    void concurrentExpiredCacheRebuildsOnlyOnce() throws Exception {
        // 验收:TTL 过期瞬间并发 32 线程,探测(single-flight 以 PATH 解析次数计)只执行一次,
        // 且所有线程拿到同一快照
        AtomicInteger pathReads = new AtomicInteger();
        ProbeEnv countingEnv = new ProbeEnv() {
            @Override
            public String get(String key) {
                if ("PATH".equals(key)) {
                    pathReads.incrementAndGet();
                }
                return null;
            }

            @Override
            public String getProperty(String key) {
                return "os.name".equals(key) ? "Windows 10" : null;
            }
        };
        SystemSdkProber singleFlight = new SystemSdkProber(executor, 50L, () -> 1_000L,
                ProbeDiagnostics.NOOP, PathReader.DEFAULT, countingEnv);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<LanguageEnum, Map<String, String>>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return singleFlight.probeVersions();
            }));
        }
        start.countDown();
        Map<LanguageEnum, Map<String, String>> first = null;
        for (Future<Map<LanguageEnum, Map<String, String>>> future : futures) {
            Map<LanguageEnum, Map<String, String>> result = future.get(10, TimeUnit.SECONDS);
            if (first == null) {
                first = result;
            } else {
                assertThat(result).isSameAs(first);
            }
        }
        pool.shutdownNow();
        assertThat(pathReads.get()).isEqualTo(1);
    }

    @Test
    void normalizedVersionsMatchCatalogFormat() {
        assertThat(SystemSdkProber.normalizeJavaVersion("17.0.9+7")).isEqualTo("17.0.9");
        assertThat(SystemSdkProber.normalizeJavaVersion(" 17.0.9+7 ")).isEqualTo("17.0.9");
        assertThat(SystemSdkProber.normalizeJavaVersion("17.0.9")).isEqualTo("17.0.9");
        assertThat(SystemSdkProber.normalizeJavaVersion("")).isEmpty();
        assertThat(SystemSdkProber.normalizeJavaVersion(null)).isNull();
    }

    @Test
    void emptyPathNeverExecutes() {
        prober.probeJava(null, List.of());
        prober.probeNode(List.of(), null);
        prober.probePython(List.of());
        prober.probeGo(List.of());
        verifyNoInteractions(executor);
    }
}
