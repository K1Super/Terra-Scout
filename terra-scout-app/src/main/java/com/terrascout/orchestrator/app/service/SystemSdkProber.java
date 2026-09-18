package com.terrascout.orchestrator.app.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.env.ProcessExecutor;

/**
 * 系统级已装 SDK 探测（SDK 管理 installed 语义补充，R29/R30）。
 *
 * <h2>平台约束（P1-4）</h2>
 * <p>本类当前实现仅面向 Windows（依赖 .exe 命名、Program Files、NVM_HOME、%APPDATA%、
 * Python Launcher 等）。在非 Windows 平台上 {@link #probeVersions()} 返回空 Map，
 * 并通过 {@link ProbeDiagnostics} 记录一次平台拒绝事件（WARN 级）。</p>
 *
 * <h2>探测策略链</h2>
 * <ul>
 *   <li>JAVA：{@code JAVA_HOME} 直读 release；PATH 中每个 java.exe —— java.exe 与 release 同目录
 *       （home 直挂）或其父目录为 home（bin 子目录布局）即命中，优先读文件避免拉起进程；
 *       否则（Oracle javapath stub / 软链，两处均无 release）对<b>该绝对路径 exe</b> 执行一次
 *       {@code -XshowSettings:properties -version} 解析 {@code java.home}/{@code java.version}
 *       （stdout+stderr 合并解析——实测该命令全部输出在 stderr），整个探测周期仅执行一次；
 *       末尾对已发现 home 的<b>父目录一层兄弟扫描</b>（凭 release + bin/java.exe 判定），
 *       覆盖「同一管理目录并列多版本」布局（如 D:\Deps\jdk-11.0.19 与 jdk-17.0.12 只有其一在 PATH）；
 *       另扫标准安装根（Program Files\Java / Eclipse Adoptium / Amazon Corretto / Microsoft /
 *       Program Files (x86)\Java / ~\.jdks，P1-5：64 位根优先取 ProgramW6432）；</li>
 *   <li>NODE：PATH 中每个 node.exe —— 对<b>绝对路径 exe</b> 执行 {@code -p process.execPath} 解析真实 home
 *       （nvm 软链），{@code -p process.version} 取版本（v 前缀剔除），任一步失败回退 exe 目录；
 *       nvm 根目录（NVM_HOME 显式即权威，否则 %APPDATA%\nvm，P2-6）下每个 v* 目录含 node.exe
 *       即一个版本（目录名即版本，零进程）；</li>
 *   <li>PYTHON：优先 {@code py -0p}（Python Launcher，一次列出全部解释器版本与路径，
 *       对 PATH 命中的 py.exe 逐绝对路径调用）；失败回退 PATH 中<b>每一个</b> python.exe 的
 *       {@code --version}（P0-4，版本精度 x.y.z，与 launcher 的 x.y 主次版本不同属环境固有差异）；</li>
 *   <li>GO：PATH 中每个 go.exe —— GOROOT 取 exe 上两级，版本优先读 {@code {GOROOT}/VERSION}
 *       文件（零进程），否则对<b>绝对路径 exe</b> 执行 {@code go version} 解析；末尾对已发现
 *       GOROOT 的父目录一层兄弟扫描（VERSION + bin/go.exe 判定）覆盖多 GOROOT 并列；</li>
 *   <li>版本归一化：Java 去 {@code +build} 后缀；Node 去 {@code v} 前缀；Go 去 {@code go} 前缀。</li>
 * </ul>
 *
 * <h2>优先级与冲突策略（P2-4/P2-5）</h2>
 * <ul>
 *   <li>版本键为归一化字符串；同一版本不同 home 时保留<b>先发现者</b>，并触发
 *       {@link ProbeDiagnostics#recordDuplicateVersion}。</li>
 *   <li>优先级顺序：JAVA_HOME / py -0p &gt; PATH 顺序 &gt; 兄弟扫描 / nvm 根 &gt; 标准安装根。</li>
 *   <li>返回 Map 使用 LinkedHashMap 保序（不可变视图，P0-2），展示层可直接依赖顺序。</li>
 * </ul>
 *
 * <h2>性能与并发契约</h2>
 * <ul>
 *   <li>结果带 TTL 缓存（默认 {@link #DEFAULT_TTL_MS}）规避 2s 轮询反复拉起进程；
 *       P1-1：{@code synchronized} 重建闸门 + 双检 single-flight，缓存过期瞬间仅一个线程
 *       执行探测，读取路径无锁；</li>
 *   <li>每次进程探测显式超时 {@link #PROBE_TIMEOUT_MS}（P1-2），禁止无限挂起；</li>
 *   <li>时钟为单调毫秒（{@code System.nanoTime} 折算，P2-7），负 elapsed（时钟回拨）判为过期；
 *       兄弟目录扫描有 {@code SCAN_LIMIT} 条目上限（P3-7）。</li>
 * </ul>
 *
 * <h2>可观测性（P1-3）</h2>
 * <p>每一处降级（进程异常 / IO 异常 / 扫描跳过 / 同版本冲突）均产生 {@link ProbeDiagnostics}
 * 事件；默认 NOOP，宿主经 {@link ProbeDiagnostics#slf4j()} 桥接 SLF4J。</p>
 *
 * <p>探测命令面（R30）与任务装配面（D-008）分离：本类仅调用
 * {@link ProcessExecutor#executeProbe} 且命令与参数全部硬编码只读，装配命令面不受影响。</p>
 */
public class SystemSdkProber {

    /** 已定位系统 SDK：归一化版本 + 安装 home。 */
    public record LocatedSdk(String version, String home) {
    }

    /** 探测缓存默认 TTL（毫秒）。 */
    public static final long DEFAULT_TTL_MS = 30_000L;

    /** 单次 SDK 探测命令显式超时（P1-2）：超出即销毁进程并记失败。 */
    static final long PROBE_TIMEOUT_MS = 2_000L;

    /** 兄弟/根目录扫描条目上限（P3-7）：防病态目录拖垮探测。 */
    private static final int SCAN_LIMIT = 10_000;

    private static final Pattern RELEASE_JAVA_VERSION =
            Pattern.compile("^JAVA_VERSION=\"?([^\"\\r\\n]+?)\"?$");
    private static final Pattern SHOWSETTINGS_HOME =
            Pattern.compile("^\\s*java\\.home\\s*=\\s*(.+?)\\s*$");
    private static final Pattern SHOWSETTINGS_VERSION =
            Pattern.compile("^\\s*java\\.version\\s*=\\s*(.+?)\\s*$");
    private static final Pattern VERSION_BANNER_QUOTED = Pattern.compile("\"([0-9][^\"]*)\"");
    private static final Pattern LEADING_V = Pattern.compile("^v");
    // P3-1：\- 在正则中与 - 等价，剔除无效转义
    private static final Pattern PY_LAUNCHER_LINE = Pattern.compile("^-V:(\\S+)\\s+\\*?\\s+(.+)$");
    private static final Pattern PY_VERSION_OUT = Pattern.compile("Python\\s+(\\S+)");
    private static final Pattern GO_VERSION_FILE = Pattern.compile("^go(\\d\\S+)$");
    private static final Pattern GO_VERSION_BANNER = Pattern.compile("go version go(\\S+)");

    /** 探测常量集中定义（P3-5）：可执行文件名 / 布局目录 / 环境与属性键。 */
    private static final class ProbeConstants {
        static final String EXE_JAVA = "java.exe";
        static final String EXE_NODE = "node.exe";
        static final String EXE_PY = "py.exe";
        static final String EXE_PYTHON = "python.exe";
        static final String EXE_GO = "go.exe";
        static final String DIR_BIN = "bin";
        static final String FILE_RELEASE = "release";
        static final String FILE_VERSION = "VERSION";
        static final String DIR_JDKS = ".jdks";
        static final String DIR_NVM = "nvm";
        static final String DIR_JAVA = "Java";
        static final String DIR_ADOPTIUM = "Eclipse Adoptium";
        static final String DIR_CORRETTO = "Amazon Corretto";
        static final String DIR_MICROSOFT = "Microsoft";
        static final String ENV_JAVA_HOME = "JAVA_HOME";
        static final String ENV_NVM_HOME = "NVM_HOME";
        static final String ENV_APPDATA = "APPDATA";
        static final String ENV_PATH = "PATH";
        static final String ENV_PROGRAM_FILES = "ProgramFiles";
        static final String ENV_PROGRAM_FILES_X86 = "ProgramFiles(x86)";
        static final String ENV_PROGRAM_W6432 = "ProgramW6432";
        static final String PROP_USER_HOME = "user.home";
        static final String PROP_OS_NAME = "os.name";

        private ProbeConstants() {
        }
    }

    private final ProcessExecutor processExecutor;
    private final long ttlMs;
    private final LongSupplier clock;
    private final ProbeDiagnostics diagnostics;
    private final PathReader reader;
    private final ProbeEnv env;

    private final Object rebuildLock = new Object();

    private volatile Map<LanguageEnum, Map<String, String>> cache;
    private volatile long cachedAt;

    /** 生产装配：默认 TTL + 单调钟 + 静默诊断（NOOP）。 */
    public SystemSdkProber(ProcessExecutor processExecutor) {
        this(processExecutor, ProbeDiagnostics.NOOP);
    }

    /** 生产装配：指定诊断实现（如 {@link ProbeDiagnostics#slf4j()} 桥接）。 */
    public SystemSdkProber(ProcessExecutor processExecutor, ProbeDiagnostics diagnostics) {
        this(processExecutor, DEFAULT_TTL_MS, SystemSdkProber::monotonicMillis, diagnostics,
                PathReader.DEFAULT, ProbeEnv.system());
    }

    SystemSdkProber(ProcessExecutor processExecutor, long ttlMs, LongSupplier clock) {
        this(processExecutor, ttlMs, clock, ProbeDiagnostics.NOOP, PathReader.DEFAULT, ProbeEnv.system());
    }

    SystemSdkProber(ProcessExecutor processExecutor, long ttlMs, LongSupplier clock,
                    ProbeDiagnostics diagnostics, PathReader reader, ProbeEnv env) {
        this.processExecutor = Objects.requireNonNull(processExecutor, "processExecutor 不能为 null");
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs 必须为正");
        }
        this.ttlMs = ttlMs;
        this.clock = Objects.requireNonNull(clock, "clock 不能为 null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics 不能为 null");
        this.reader = Objects.requireNonNull(reader, "reader 不能为 null");
        this.env = Objects.requireNonNull(env, "env 不能为 null");
    }

    /** P2-7：单调毫秒钟（nanoTime 折算，不受系统时钟回拨影响）。 */
    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * 探测本机系统级已装 SDK：language → version → home（保序不可变视图）。
     *
     * <p>契约：P1-4 非 Windows 平台返回空 Map 并记录平台拒绝；P1-1 读取路径无锁，
     * 过期时经 {@code synchronize(rebuildLock)} 双检 single-flight（同一时刻仅一个线程探测）；
     * P2-7 负 elapsed（时钟回拨）判为过期而非永久命中。</p>
     */
    public Map<LanguageEnum, Map<String, String>> probeVersions() {
        if (!isWindows()) {
            diagnostics.recordProbeFailure("SYSTEM", "platform-guard",
                    new UnsupportedOperationException("非 Windows 平台暂不支持系统 SDK 探测"));
            return Map.of();
        }
        long now = clock.getAsLong();
        Map<LanguageEnum, Map<String, String>> snapshot = cache;
        if (snapshot != null && fresh(now)) {
            return snapshot;
        }
        synchronized (rebuildLock) {
            long now2 = clock.getAsLong();
            snapshot = cache;
            if (snapshot != null && fresh(now2)) {
                return snapshot; // 其它线程已完成重建
            }
            Map<LanguageEnum, Map<String, String>> rebuilt = doProbe();
            cache = rebuilt; // 写序：cache → cachedAt，读侧据此保证可见性
            cachedAt = clock.getAsLong();
            return rebuilt;
        }
    }

    /** 缓存新鲜判定（P2-7）：单调钟下 elapsed 恒非负；时钟回拨的负值判为过期。 */
    private boolean fresh(long now) {
        long elapsed = now - cachedAt;
        return elapsed >= 0 && elapsed < ttlMs;
    }

    /** 全语言探测编排：PATH 仅解析一次（P3-3），每语言独立降级互不影响。 */
    private Map<LanguageEnum, Map<String, String>> doProbe() {
        List<Path> pathDirs = pathDirs();
        Map<String, String> javaFound = probeJava(env.get(ProbeConstants.ENV_JAVA_HOME), pathDirs);
        scanStandardJavaRoots(javaFound);
        Map<LanguageEnum, Map<String, String>> result = new EnumMap<>(LanguageEnum.class);
        result.put(LanguageEnum.JAVA, orderedImmutable(javaFound));
        result.put(LanguageEnum.NODE, orderedImmutable(probeNode(pathDirs, nvmRoot())));
        result.put(LanguageEnum.PYTHON, orderedImmutable(probePython(pathDirs)));
        result.put(LanguageEnum.GO, orderedImmutable(probeGo(pathDirs)));
        return result;
    }

    /** 有序不可变视图（P0-2）：保留探测插入顺序（优先级语义），屏蔽外部写入。 */
    private static <K, V> Map<K, V> orderedImmutable(Map<K, V> src) {
        @SuppressWarnings("unchecked")
        Map<K, V> ordered = (Map<K, V>) Collections.unmodifiableMap(new LinkedHashMap<>(src));
        return ordered;
    }

    /**
     * 版本-路径写入（P0-3）：同版本不同 home 保留先发现者，并记录冲突事件。
     *
     * @param source 来源标识（JAVA_HOME / PATH / py-launcher / sibling / nvm-root …）用于诊断定位
     */
    private void putOrRecord(Map<String, String> found, String version, String home, String source) {
        String prev = found.putIfAbsent(version, home);
        if (prev != null && !prev.equals(home)) {
            diagnostics.recordDuplicateVersion(source, version, prev, home);
        }
    }

    /** JAVA 探测：JAVA_HOME 直指 home；PATH 目录含 java.exe 时先读 release，其次执行解析；末尾兄弟扫描。 */
    Map<String, String> probeJava(String javaHomeEnv, List<Path> pathDirs) {
        Map<String, String> found = new LinkedHashMap<>();
        if (javaHomeEnv != null && !javaHomeEnv.isBlank()) {
            Path home = safePath(javaHomeEnv.trim());
            if (home != null) {
                String version = readJavaRelease(home);
                if (version != null) {
                    putOrRecord(found, version, home.toString(), "JAVA_HOME");
                }
            }
        }
        ShowSettingsResolver resolver = new ShowSettingsResolver();
        for (Path dir : pathDirs) {
            Path exe = dir.resolve(ProbeConstants.EXE_JAVA);
            if (!reader.isRegularFile(exe)) {
                continue;
            }
            // PATH 条目两种布局均可命中：home 直挂（java.exe 与 release 同目录）
            // 或 bin 子目录（JAVA_HOME\bin 布局，release 在 bin 的父目录），优先读文件避免拉起进程
            Path candidate = exe.getParent();
            Path home = candidate;
            String version = candidate == null ? null : readJavaRelease(candidate);
            if (version == null && candidate != null && candidate.getParent() != null) {
                String parentVersion = readJavaRelease(candidate.getParent());
                if (parentVersion != null) {
                    version = parentVersion;
                    home = candidate.getParent();
                }
            }
            if (version == null) {
                // javapath stub / 软链场景：整个探测周期只执行一次 -XshowSettings（P0-1：绑绝对路径）
                LocatedSdk resolved = resolver.resolve(exe);
                if (resolved != null) {
                    Path resolvedHome = safePath(resolved.home);
                    home = resolvedHome == null ? home : resolvedHome;
                    version = resolved.version;
                }
            }
            if (version != null && home != null) {
                putOrRecord(found, version, home.toString(), "PATH");
            }
        }
        // 同父目录兄弟扫描：覆盖「同一管理目录并列多版本」布局，如 D:\Deps 下 jdk-11.0.19 与 jdk-17.0.12
        for (Path parent : parentRootsOf(found)) {
            scanJavaSiblings(parent, found);
        }
        return found;
    }

    /** NODE 探测：PATH 每个 node.exe（execPath/version 实解）+ nvm 根目录 v* 多版本。 */
    Map<String, String> probeNode(List<Path> pathDirs, Path nvmRoot) {
        Map<String, String> found = new LinkedHashMap<>();
        for (Path dir : pathDirs) {
            Path exe = dir.resolve(ProbeConstants.EXE_NODE);
            if (!reader.isRegularFile(exe)) {
                continue;
            }
            LocatedSdk located = locateNode(exe);
            if (located != null) {
                putOrRecord(found, located.version, located.home, "PATH");
            }
        }
        scanNvmRoot(nvmRoot, found);
        return found;
    }

    /** PYTHON 探测：py -0p（Python Launcher 一次列出全部解释器）优先，失败回退 PATH 全部 python.exe。 */
    Map<String, String> probePython(List<Path> pathDirs) {
        Map<String, String> found = new LinkedHashMap<>();
        if (probePythonViaLauncher(found, pathDirs)) {
            return found;
        }
        // P0-4：Launcher 不可用时逐个探测 PATH 命中的 python.exe（绑绝对路径），多版本全收录；
        // TTL 缓存 30s 摊薄进程成本，可接受
        for (Path dir : pathDirs) {
            Path exe = dir.resolve(ProbeConstants.EXE_PYTHON);
            if (!reader.isRegularFile(exe) || exe.getParent() == null) {
                continue;
            }
            String version = versionOfPythonCommand(exe);
            if (version != null) {
                putOrRecord(found, version, exe.getParent().toString(), "PATH");
            }
        }
        return found;
    }

    /** GO 探测：PATH 每个 go.exe（GOROOT=VERSION 文件优先，go version 回退）+ 兄弟目录多 GOROOT。 */
    Map<String, String> probeGo(List<Path> pathDirs) {
        Map<String, String> found = new LinkedHashMap<>();
        LinkedHashSet<Path> goroots = new LinkedHashSet<>();
        for (Path dir : pathDirs) {
            Path exe = dir.resolve(ProbeConstants.EXE_GO);
            if (!reader.isRegularFile(exe) || exe.getParent() == null
                    || exe.getParent().getParent() == null) {
                continue;
            }
            Path goroot = exe.getParent().getParent();
            String version = readGoVersionFile(goroot);
            if (version == null) {
                version = versionOfGoCommand(exe);
            }
            if (version != null) {
                putOrRecord(found, version, goroot.toString(), "PATH");
                goroots.add(goroot);
            }
        }
        for (Path goroot : goroots) {
            if (goroot.getParent() != null) {
                scanGoSiblings(goroot.getParent(), found);
            }
        }
        return found;
    }

    /** 执行 {@code <exe> -XshowSettings:properties -version} 解析 java.home 与 java.version（探测面 java）。 */
    private LocatedSdk locateJavaViaShowSettings(Path exe) {
        String exePath = displayPath(exe);
        String command = exePath + " -XshowSettings:properties -version";
        long t0 = clock.getAsLong();
        ProcessExecutor.Result result;
        try {
            result = processExecutor.executeProbe(
                    spec(exePath, List.of("-XshowSettings:properties", "-version")),
                    null, null, PROBE_TIMEOUT_MS);
        } catch (RuntimeException e) {
            diagnostics.recordProbeFailure("JAVA", command, e);
            return null;
        }
        diagnostics.recordProbeSuccess("JAVA", command, clock.getAsLong() - t0);
        if (result == null) {
            return null;
        }
        try {
            // 实测：java -XshowSettings:properties -version 的全部输出在 stderr（含 java.home/java.version），
            // stdout 为空；合并两流解析，避免按流分离导致 home 缺失
            String merged = merged(result);
            String home = null;
            String version = null;
            for (String line : merged.split("\\R")) {
                Matcher homeMatch = SHOWSETTINGS_HOME.matcher(line);
                Matcher versionMatch = SHOWSETTINGS_VERSION.matcher(line);
                if (home == null && homeMatch.matches()) {
                    home = homeMatch.group(1).trim();
                }
                if (version == null && versionMatch.matches()) {
                    version = versionMatch.group(1).trim();
                }
            }
            if (version == null) {
                Matcher banner = VERSION_BANNER_QUOTED.matcher(
                        result.stderr() == null ? "" : result.stderr());
                if (banner.find()) {
                    version = banner.group(1);
                }
            }
            if (home == null || version == null) {
                return null;
            }
            return new LocatedSdk(normalizeJavaVersion(version), home);
        } catch (RuntimeException e) {
            diagnostics.recordProbeFailure("JAVA", command + "(解析)", e);
            return null;
        }
    }

    /**
     * -XshowSettings 惰性执行守卫（P2-3）。
     *
     * <p>语义：单个 {@link #probeJava} 探测周期内最多执行一次。失败结果（null）在本周期内被复用，
     * 不重试；下一缓存 TTL 到期重新探测时会重新尝试。</p>
     */
    private final class ShowSettingsResolver {
        private boolean attempted;
        private LocatedSdk resolved;

        LocatedSdk resolve(Path exe) {
            if (!attempted) {
                attempted = true;
                resolved = locateJavaViaShowSettings(exe);
            }
            return resolved;
        }
    }

    /** 执行 {@code <exe> -p process.execPath / process.version}；home 解析失败回退 exe 所在目录。 */
    private LocatedSdk locateNode(Path exe) {
        String exePath = displayPath(exe);
        String home = exe.getParent() == null ? null : exe.getParent().toString();
        String version = null;
        String execPathCommand = exePath + " -p process.execPath";
        try {
            long t0 = clock.getAsLong();
            ProcessExecutor.Result execPath = processExecutor.executeProbe(
                    spec(exePath, List.of("-p", "process.execPath")), null, null, PROBE_TIMEOUT_MS);
            diagnostics.recordProbeSuccess("NODE", execPathCommand, clock.getAsLong() - t0);
            if (execPath.exitCode() == 0 && execPath.stdout() != null) {
                String real = execPath.stdout().trim();
                Path realPath = safePath(real);
                if (realPath != null && realPath.getParent() != null) {
                    home = realPath.getParent().toString();
                }
            }
        } catch (RuntimeException e) {
            // 尽力而为：execPath 失败沿用 exe 所在目录
            diagnostics.recordProbeFailure("NODE", execPathCommand, e);
        }
        String versionCommand = exePath + " -p process.version";
        try {
            long t0 = clock.getAsLong();
            ProcessExecutor.Result versionResult = processExecutor.executeProbe(
                    spec(exePath, List.of("-p", "process.version")), null, null, PROBE_TIMEOUT_MS);
            diagnostics.recordProbeSuccess("NODE", versionCommand, clock.getAsLong() - t0);
            if (versionResult.exitCode() == 0 && versionResult.stdout() != null) {
                String raw = LEADING_V.matcher(versionResult.stdout().trim()).replaceFirst("");
                if (!raw.isEmpty()) {
                    version = raw;
                }
            }
        } catch (RuntimeException e) {
            // 尽力而为：版本获取失败按不可识别处理
            diagnostics.recordProbeFailure("NODE", versionCommand, e);
        }
        if (home == null || version == null) {
            return null;
        }
        return new LocatedSdk(version, home);
    }

    /** 执行 {@code <py.exe> -0p} 解析全部解释器（版本精度 x.y）；解析到条目返回 true，launcher 不可用返回 false。 */
    private boolean probePythonViaLauncher(Map<String, String> found, List<Path> pathDirs) {
        Path py = firstExecutable(ProbeConstants.EXE_PY, pathDirs);
        if (py == null) {
            return false;
        }
        String pyPath = displayPath(py);
        String command = pyPath + " -0p";
        long t0 = clock.getAsLong();
        ProcessExecutor.Result result;
        try {
            result = processExecutor.executeProbe(spec(pyPath, List.of("-0p")),
                    null, null, PROBE_TIMEOUT_MS);
        } catch (RuntimeException e) {
            diagnostics.recordProbeFailure("PYTHON", command, e);
            return false;
        }
        diagnostics.recordProbeSuccess("PYTHON", command, clock.getAsLong() - t0);
        if (result == null || result.exitCode() != 0 || result.stdout() == null) {
            return false;
        }
        boolean any = false;
        for (String line : result.stdout().split("\\R")) {
            Matcher matcher = PY_LAUNCHER_LINE.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            Path exe = safePath(matcher.group(2).trim());
            if (exe != null && exe.getParent() != null) {
                putOrRecord(found, matcher.group(1), exe.getParent().toString(), "py-launcher");
                any = true;
            }
        }
        return any;
    }

    /** 执行 {@code <exe> --version} 解析版本（输出 "Python 3.11.9"；P3-2 先判退出码再匹配）。 */
    private String versionOfPythonCommand(Path exe) {
        String exePath = displayPath(exe);
        String command = exePath + " --version";
        long t0 = clock.getAsLong();
        ProcessExecutor.Result result;
        try {
            result = processExecutor.executeProbe(spec(exePath, List.of("--version")),
                    null, null, PROBE_TIMEOUT_MS);
        } catch (RuntimeException e) {
            diagnostics.recordProbeFailure("PYTHON", command, e);
            return null;
        }
        diagnostics.recordProbeSuccess("PYTHON", command, clock.getAsLong() - t0);
        if (result == null || result.exitCode() != 0) {
            return null;
        }
        Matcher matcher = PY_VERSION_OUT.matcher(merged(result));
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 执行 {@code <exe> version} 解析版本（输出 "go version go1.26.2 windows/386"）。 */
    private String versionOfGoCommand(Path exe) {
        String exePath = displayPath(exe);
        String command = exePath + " version";
        long t0 = clock.getAsLong();
        ProcessExecutor.Result result;
        try {
            result = processExecutor.executeProbe(spec(exePath, List.of("version")),
                    null, null, PROBE_TIMEOUT_MS);
        } catch (RuntimeException e) {
            diagnostics.recordProbeFailure("GO", command, e);
            return null;
        }
        diagnostics.recordProbeSuccess("GO", command, clock.getAsLong() - t0);
        if (result == null || result.exitCode() != 0) {
            return null;
        }
        Matcher matcher = GO_VERSION_BANNER.matcher(merged(result));
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 读 JDK release 文件中的 JAVA_VERSION（无 release 文件 → null；release 为 ASCII，ISO_8859_1 足矣，P3-8）。 */
    private String readJavaRelease(Path home) {
        Path release = home.resolve(ProbeConstants.FILE_RELEASE);
        if (!reader.isRegularFile(release)) {
            return null;
        }
        try {
            for (String raw : reader.readAllLines(release, StandardCharsets.ISO_8859_1)) {
                Matcher matcher = RELEASE_JAVA_VERSION.matcher(raw.trim());
                if (matcher.matches()) {
                    return normalizeJavaVersion(matcher.group(1));
                }
            }
        } catch (IOException | RuntimeException e) {
            diagnostics.recordProbeFailure("JAVA", "读取 release: " + release, e);
        }
        return null;
    }

    /** 读 GOROOT 的 VERSION 文件（内容 "go1.26.2"，去 go 前缀；无文件 → null）。 */
    private String readGoVersionFile(Path goroot) {
        Path versionFile = goroot.resolve(ProbeConstants.FILE_VERSION);
        if (!reader.isRegularFile(versionFile)) {
            return null;
        }
        try {
            Matcher matcher = GO_VERSION_FILE.matcher(
                    reader.readString(versionFile, StandardCharsets.UTF_8).trim());
            return matcher.matches() ? matcher.group(1) : null;
        } catch (IOException | RuntimeException e) {
            diagnostics.recordProbeFailure("GO", "读取 VERSION: " + versionFile, e);
        }
        return null;
    }

    /** 标准 JDK 安装根一层扫描（零进程，凭 release + bin/java.exe 判定，放开箱即装的官方位置）。 */
    void scanStandardJavaRoots(Map<String, String> found) {
        for (Path root : standardJavaRoots()) {
            scanJavaSiblings(root, found);
        }
    }

    private List<Path> standardJavaRoots() {
        // P1-5：WOW64 下 32 位 JVM 读到的 ProgramFiles 是 (x86)，64 位标准目录优先取 ProgramW6432
        List<Path> roots = new ArrayList<>();
        String pf = programFilesRoot();
        addStandardRoot(roots, pf, ProbeConstants.DIR_JAVA);
        addStandardRoot(roots, pf, ProbeConstants.DIR_ADOPTIUM);
        addStandardRoot(roots, pf, ProbeConstants.DIR_CORRETTO);
        addStandardRoot(roots, pf, ProbeConstants.DIR_MICROSOFT);
        addStandardRoot(roots, env.get(ProbeConstants.ENV_PROGRAM_FILES_X86), ProbeConstants.DIR_JAVA);
        addStandardRoot(roots, env.getProperty(ProbeConstants.PROP_USER_HOME), ProbeConstants.DIR_JDKS);
        return roots;
    }

    private String programFilesRoot() {
        String pf64 = env.get(ProbeConstants.ENV_PROGRAM_W6432);
        if (pf64 != null && !pf64.isBlank()) {
            return pf64;
        }
        return env.get(ProbeConstants.ENV_PROGRAM_FILES);
    }

    private static void addStandardRoot(List<Path> roots, String base, String child) {
        if (base == null || base.isBlank()) {
            return;
        }
        Path basePath = safePath(base.trim());
        if (basePath != null) {
            roots.add(basePath.resolve(child));
        }
    }

    /** 根目录一层兄弟扫描（JDK）：子目录含 release（读 JAVA_VERSION）且 bin/java.exe 即认定 home。 */
    private void scanJavaSiblings(Path root, Map<String, String> found) {
        if (root == null || !reader.isDirectory(root)) {
            return;
        }
        try (Stream<Path> children = reader.list(root)) {
            scanLimited(children, child -> {
                if (reader.isDirectory(child)
                        && reader.isRegularFile(child.resolve(ProbeConstants.DIR_BIN).resolve(ProbeConstants.EXE_JAVA))) {
                    String version = readJavaRelease(child);
                    if (version != null) {
                        putOrRecord(found, version, child.toString(), "sibling/standard-root");
                    }
                }
            });
        } catch (IOException | RuntimeException e) {
            diagnostics.recordScanSkipped("JAVA", root, e);
        }
    }

    /** 根目录一层兄弟扫描（GOROOT）：子目录含 VERSION（内容 goX.Y.Z）且 bin/go.exe 即认定。 */
    private void scanGoSiblings(Path root, Map<String, String> found) {
        if (root == null || !reader.isDirectory(root)) {
            return;
        }
        try (Stream<Path> children = reader.list(root)) {
            scanLimited(children, child -> {
                if (reader.isDirectory(child)
                        && reader.isRegularFile(child.resolve(ProbeConstants.DIR_BIN).resolve(ProbeConstants.EXE_GO))) {
                    String version = readGoVersionFile(child);
                    if (version != null) {
                        putOrRecord(found, version, child.toString(), "sibling/standard-root");
                    }
                }
            });
        } catch (IOException | RuntimeException e) {
            diagnostics.recordScanSkipped("GO", root, e);
        }
    }

    /** nvm 根目录 v* 多版本扫描：目录名 vX.Y.Z 即版本（零进程），目录含 node.exe 才认定。 */
    private void scanNvmRoot(Path nvmRoot, Map<String, String> found) {
        if (nvmRoot == null || !reader.isDirectory(nvmRoot)) {
            return;
        }
        try (Stream<Path> children = reader.list(nvmRoot)) {
            scanLimited(children, child -> {
                String name = child.getFileName() == null ? "" : child.getFileName().toString();
                if (reader.isDirectory(child) && name.startsWith("v")
                        && reader.isRegularFile(child.resolve(ProbeConstants.EXE_NODE))) {
                    String version = LEADING_V.matcher(name).replaceFirst("");
                    if (!version.isEmpty()) {
                        putOrRecord(found, version, child.toString(), "nvm-root");
                    }
                }
            });
        } catch (IOException | RuntimeException e) {
            diagnostics.recordScanSkipped("NODE", nvmRoot, e);
        }
    }

    /** 受条目上限保护的目录遍历（P3-7）：超过上限截断；IO 异常向上抛由调用方降级记录。 */
    private static void scanLimited(Stream<Path> children, Consumer<Path> action) {
        Iterator<Path> it = children.iterator();
        int count = 0;
        while (it.hasNext() && count++ < SCAN_LIMIT) {
            action.accept(it.next());
        }
    }

    /** 已发现 home 的父目录集合（去重、跳过根），用于兄弟目录扫描。 */
    private static LinkedHashSet<Path> parentRootsOf(Map<String, String> found) {
        LinkedHashSet<Path> parents = new LinkedHashSet<>();
        for (String homeStr : found.values()) {
            Path home = safePath(homeStr);
            if (home != null && home.getParent() != null) {
                parents.add(home.getParent());
            }
        }
        return parents;
    }

    /** nvm 根目录（P2-6）：显式设置 NVM_HOME 即权威，无效不回退；未设置才用 %APPDATA%\nvm。 */
    private Path nvmRoot() {
        String nvmHome = env.get(ProbeConstants.ENV_NVM_HOME);
        if (nvmHome != null && !nvmHome.isBlank()) {
            Path custom = safePath(nvmHome.trim());
            if (custom != null) {
                return custom;
            }
            diagnostics.recordProbeFailure("NODE", "NVM_HOME=" + nvmHome.trim(),
                    new IllegalArgumentException("无效的 NVM_HOME 路径"));
            return null;
        }
        Path appData = safePath(env.get(ProbeConstants.ENV_APPDATA));
        return appData == null ? null : appData.resolve(ProbeConstants.DIR_NVM);
    }

    /** PATH 中首个存在的可执行文件（P0-1 前置：探测必须锚定具体 exe）。 */
    private Path firstExecutable(String exeName, List<Path> pathDirs) {
        for (Path dir : pathDirs) {
            Path candidate = dir.resolve(exeName);
            if (reader.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Java 版本归一化：去除 +build 后缀（17.0.9+7 → 17.0.9）。 */
    static String normalizeJavaVersion(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        int plus = trimmed.indexOf('+');
        return plus >= 0 ? trimmed.substring(0, plus) : trimmed;
    }

    /** stdout 与 stderr 合并（探测命令输出流位置不可假设；P3-4 StringBuilder 拼接免冗余复制）。 */
    private static String merged(ProcessExecutor.Result result) {
        String out = result.stdout() == null ? "" : result.stdout();
        String err = result.stderr() == null ? "" : result.stderr();
        return new StringBuilder(out.length() + err.length() + 1)
                .append(out).append('\n').append(err).toString();
    }

    private static CommandSpec spec(String command, List<String> args) {
        CommandSpec spec = new CommandSpec();
        spec.setCommand(command);
        spec.setArgs(args);
        return spec;
    }

    private List<Path> pathDirs() {
        List<Path> dirs = new ArrayList<>();
        String path = env.get(ProbeConstants.ENV_PATH);
        if (path == null || path.isBlank()) {
            return dirs;
        }
        for (String part : path.split(Pattern.quote(File.pathSeparator))) {
            if (part == null || part.isBlank()) {
                continue;
            }
            Path dir = safePath(part.trim());
            if (dir != null) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /**
     * 容错解析路径（P3-6）：剥离 Windows 长路径前缀 {@code \\?\} 后解析（保留超长路径支持），
     * UNC 前缀还原为 {@code \\server\share}；非法字符解析失败按无效跳过。
     */
    private static Path safePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.startsWith("\\\\?\\")) {
            normalized = normalized.substring(4);
            if (normalized.startsWith("UNC\\")) {
                normalized = "\\\\" + normalized.substring(4);
            }
        }
        try {
            return Path.of(normalized);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** P0-1：探测命令必须绑定 PATH 命中的具体可执行文件绝对路径。 */
    private static String displayPath(Path exe) {
        return exe.toAbsolutePath().toString();
    }

    /** 平台守卫（P1-4）：os.name 以 windows 开头视为 Windows。 */
    private boolean isWindows() {
        String os = env.getProperty(ProbeConstants.PROP_OS_NAME);
        return os != null && os.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
