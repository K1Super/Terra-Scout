package com.terrascout.orchestrator.env;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * 命令执行器：命令白名单校验（安全红线）。
 *
 * <p>禁止字符串拼接构造命令：一律 {@code List<String>} + {@link CommandWhitelist} 校验（拦截注入）。
 * `.cmd` 需经 {@code cmd.exe /c} 调用；npm 默认追加 {@code --ignore-scripts}（防供应链脚本执行）。
 * 装配面裸命令名在 spawn 前按注入 PATH 钉为绝对路径（防 Windows 父进程 PATH 错配系统工具）。
 * 启动失败 / 超时 / 被中断 → {@code 422015 COMMAND_EXECUTION_FAILED}；非零退出码作为返回值由上层裁决。
 */
public final class ProcessExecutor implements AutoCloseable {

    /** 命令超时配置键（terrascout.command.timeout-ms，默认 10 分钟）。 */
    public static final String DEFAULT_TIMEOUT_PROPERTY = "terrascout.command.timeout-ms";
    /** 默认命令超时 10 分钟。 */
    public static final long DEFAULT_TIMEOUT_MS = 600_000L;
    private static final String IGNORE_SCRIPTS = "--ignore-scripts";

    /** 进程产生缝：测试可注入慢进程以确定性驱动超时路径（生产默认 ProcessBuilder#start）。 */
    @FunctionalInterface
    interface Spawner {
        Process start(ProcessBuilder pb) throws IOException;
    }

    private final long timeoutMs;
    private final Spawner spawner;

    public ProcessExecutor() {
        this(DEFAULT_TIMEOUT_MS, ProcessBuilder::start);
    }

    public ProcessExecutor(long timeoutMs) {
        this(timeoutMs, ProcessBuilder::start);
    }

    ProcessExecutor(long timeoutMs, Spawner spawner) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs 必须为正");
        }
        this.timeoutMs = timeoutMs;
        this.spawner = Objects.requireNonNull(spawner, "spawner 不能为 null");
    }

    /** 命令执行结果。 */
    public record Result(int exitCode, String stdout, String stderr, long durationMs) {
    }

    /**
     * 在指定目录与环境执行命令。
     *
     * @param spec    命令规格（须通过白名单，否则 403002）
     * @param workDir 工作目录（可 null = 继承）
     * @param env     注入环境变量（可 null = 纯继承；通常传 {@link EnvInjector#buildEnv} 结果）
     * @return 执行结果（含退出码）
     */
    public Result execute(CommandSpec spec, Path workDir, Map<String, String> env) {
        Objects.requireNonNull(spec, "spec 不能为 null");
        CommandWhitelist.validate(spec); // 403002，装配面
        String executable = resolveExeOnInjectedPath(
                CommandWhitelist.resolveExecutable(spec.getCommand()), env);
        return run(executable, spec, workDir, env, timeoutMs);
    }

    /**
     * 将裸文件名按注入 env 的 PATH 解析为绝对路径。
     *
     * <p>Windows 上 JDK 以裸名启动进程时，CreateProcess 按<b>父进程（JVM）PATH</b>定位
     * 可执行文件，注入到子进程 env 的 PATH 不参与解析——SDK bin 前置目录形同虚设，
     * 会错配到系统安装的同名工具（实测：系统 32 位 go.exe 搭配项目 64 位 GOROOT 秒失败）。
     * 因此装配面在 spawn 前显式按注入 PATH 逐目录钉死绝对路径，保证用的是项目 SDK。
     * 未命中时保持裸名（回退既有父进程 PATH 语义）；探测面不走本方法，维持系统探测语义。
     */
    static String resolveExeOnInjectedPath(String executable, Map<String, String> env) {
        if (executable == null || env == null || hasPathSeparator(executable)) {
            return executable;
        }
        String path = env.get(EnvInjector.PATH_VAR);
        if (path == null || path.isBlank()) {
            return executable;
        }
        for (String dir : path.split(Pattern.quote(System.getProperty("path.separator", ";")),
                -1)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir).resolve(executable);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            } catch (InvalidPathException e) {
                // 系统 PATH 常含 %SystemRoot% 等未展开条目：跳过继续探测
            }
        }
        return executable;
    }

    private static boolean hasPathSeparator(String value) {
        return value.indexOf('\\') >= 0 || value.indexOf('/') >= 0;
    }

    /**
     * 以探测面白名单执行只读的系统 SDK 探测命令：
     * {@code java/node/py/python/go} 且参数仅由 {@code SystemSdkProber} 硬编码常量提供，
     * 与任务装配面（mvn/npm/java/node）严格分离，不放大装配命令面。
     * 超时取实例默认值；探测调用应优先使用 {@link #executeProbe(CommandSpec, Path, Map, long)}
     * 显式限时（探测不得无限挂起）。
     */
    public Result executeProbe(CommandSpec spec, Path workDir, Map<String, String> env) {
        return executeProbe(spec, workDir, env, timeoutMs);
    }

    /**
     * 探测面执行（显式超时）：系统 SDK 探测命令必须显式限时，
     * 单个坏 java.exe 不得无限挂起、卡住 SDK 列表查询；超时销毁进程并抛 422015。
     *
     * @param spec      命令规格（须通过探测面白名单，否则 403002）
     * @param workDir   工作目录（可 null = 继承）
     * @param env       注入环境变量（可 null = 纯继承）
     * @param timeoutMs 单次探测超时（毫秒，必须为正；覆盖实例默认值）
     * @return 执行结果（含退出码）
     */
    public Result executeProbe(CommandSpec spec, Path workDir, Map<String, String> env, long timeoutMs) {
        Objects.requireNonNull(spec, "spec 不能为 null");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs 必须为正");
        }
        CommandWhitelist.validateProbe(spec); // 403002，探测面（逻辑名或绝对路径，文件名白名单）
        return run(CommandWhitelist.resolveProbeExecutable(spec.getCommand()), spec, workDir, env, timeoutMs);
    }

    private Result run(String exe, CommandSpec spec, Path workDir, Map<String, String> env, long timeoutMs) {
        List<String> cmd = buildOsCommand(exe, spec.getCommand(), spec.getArgs());

        ProcessBuilder pb = buildProcess(cmd, workDir, env);
        long start = System.nanoTime();
        Process process;
        try {
            process = spawner.start(pb);
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.COMMAND_EXECUTION_FAILED,
                    "命令启动失败: " + exe, e);
        }

        // 先等待（带超时），再读取输出；避免读流阻塞越过超时阈值。
        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new TerraScoutException(TerraScoutError.COMMAND_EXECUTION_FAILED,
                    "命令执行被中断: " + exe, e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new TerraScoutException(TerraScoutError.COMMAND_EXECUTION_FAILED,
                    "命令执行超时(" + timeoutMs + "ms): " + exe);
        }

        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        return new Result(process.exitValue(), stdout, stderr, durationMs);
    }

    /**
     * 组装 OS 命令行：`.cmd` 走 {@code cmd.exe /c}；npm 默认追加 --ignore-scripts。
     * 包私有静态方法，便于直测。
     */
    static List<String> buildOsCommand(String exe, String command, List<String> args) {
        List<String> out = new ArrayList<>();
        if (exe.endsWith(".cmd")) {
            out.add("cmd.exe");
            out.add("/c");
        }
        out.add(exe);
        if (args != null) {
            out.addAll(args);
        }
        if ("npm".equals(command) && !out.contains(IGNORE_SCRIPTS)) {
            out.add(IGNORE_SCRIPTS);
        }
        return out;
    }

    private static ProcessBuilder buildProcess(List<String> cmd, Path workDir, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        if (env != null) {
            pb.environment().putAll(env);
        }
        return pb;
    }

    private static String readAll(InputStream in) {
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.COMMAND_EXECUTION_FAILED,
                    "读取命令输出失败", e);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        // 无连接级资源需关闭；实现 AutoCloseable 以统一管理语义。
    }
}
