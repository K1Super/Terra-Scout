package com.terrascout.orchestrator.env;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * 命令与参数白名单校验（security.md 6.4/6.5，D-008，安全红线 1）。
 *
 * <p>命令命中白名单映射（{@code mvn→mvn.cmd} 等）；每个参数必须匹配白名单正则
 * {@code ^[A-Za-z0-9@+=:,._/\\-]+$}，该字符集覆盖 cmd.exe 全部元字符（{@code & | ; > < ` $ % ^ ( ) ! " '}），
 * 从构造上杜绝命令注入（TC-010）。拒绝一律抛 403002 COMMAND_REJECTED。
 */
public final class CommandWhitelist {

    /** 白名单面（R30）：装配面限 P0 工作面（mvn/npm/java/node/go/pip/python）；探测面仅限系统 SDK 只读探测。 */
    public enum Surface {
        ASSEMBLY,
        PROBE
    }

    /** 参数白名单正则（D-008）：仅允许安全字符，覆盖 cmd.exe 全部元字符。 */
    private static final String ARG_PATTERN_STR = "^[A-Za-z0-9@+=:,._/\\\\-]+$";
    private static final Pattern ARG_PATTERN = Pattern.compile(ARG_PATTERN_STR);

    /** 单参数最大长度（openapi CommandSpec）。 */
    static final int MAX_ARG_LENGTH = 1024;
    /** 参数最大数量（openapi CommandSpec）。 */
    static final int MAX_ARGS = 64;

    /** 装配面命令名 → 实际可执行文件（security 6.5 白名单，D-008；R48 放行 go/pip/python 支撑 Go/Python 全链路装配）。 */
    private static final Map<String, String> ASSEMBLY_EXECUTABLE_BY_COMMAND = Map.of(
            "mvn", "mvn.cmd",
            "npm", "npm.cmd",
            "java", "java.exe",
            "node", "node.exe",
            "go", "go.exe",
            "pip", "pip.exe",
            "python", "python.exe");

    /**
     * 探测面命令名 → 实际可执行文件（R30）：仅由 SystemSdkProber 的硬编码只读指令使用；
     * py（Python Launcher）不在装配面，任务执行面保持 D-008 原状。
     */
    private static final Map<String, String> PROBE_EXECUTABLE_BY_COMMAND = Map.of(
            "java", "java.exe",
            "node", "node.exe",
            "py", "py.exe",
            "python", "python.exe",
            "go", "go.exe");

    private CommandWhitelist() {
    }

    private static Map<String, String> executables(Surface surface) {
        return surface == Surface.PROBE ? PROBE_EXECUTABLE_BY_COMMAND : ASSEMBLY_EXECUTABLE_BY_COMMAND;
    }

    /** 命令名是否在装配面白名单内。 */
    public static boolean isAllowedCommand(String command) {
        return isAllowedCommand(command, Surface.ASSEMBLY);
    }

    /** 命令名是否在指定面白名单内。 */
    public static boolean isAllowedCommand(String command, Surface surface) {
        return command != null && executables(surface).containsKey(command);
    }

    /** 解析命令名为装配面可执行文件；不在白名单返回 null。 */
    public static String resolveExecutable(String command) {
        return resolveExecutable(command, Surface.ASSEMBLY);
    }

    /** 解析命令名为指定面可执行文件；不在白名单返回 null。 */
    public static String resolveExecutable(String command, Surface surface) {
        return command == null ? null : executables(surface).get(command);
    }

    /** 参数是否安全（非 null、长度合法、全部字符在正则内）。 */
    public static boolean isAllowedArg(String arg) {
        return arg != null && arg.length() <= MAX_ARG_LENGTH && ARG_PATTERN.matcher(arg).matches();
    }

    /** 返回首个非法原因；合法返回 null。 */
    public static String denyReason(String arg) {
        if (arg == null) {
            return "参数为 null";
        }
        if (arg.length() > MAX_ARG_LENGTH) {
            return "参数超长(>" + MAX_ARG_LENGTH + "): " + arg;
        }
        if (!ARG_PATTERN.matcher(arg).matches()) {
            return "包含非法字符(防注入): " + arg;
        }
        return null;
    }

    /**
     * 全量校验命令与参数（装配面）；任一不合法抛 {@code 403002 COMMAND_REJECTED}。
     */
    public static void validate(CommandSpec spec) {
        validate(spec, Surface.ASSEMBLY);
    }

    /**
     * 全量校验命令与参数（指定面）；任一不合法抛 {@code 403002 COMMAND_REJECTED}。
     */
    public static void validate(CommandSpec spec, Surface surface) {
        Objects.requireNonNull(spec, "spec 不能为 null");
        String command = spec.getCommand();
        if (!isAllowedCommand(command, surface)) {
            throw rejected("命令不在白名单: " + command, command, null);
        }
        validateArgs(spec, command);
    }

    /**
     * 探测面全量校验（R30 + P0-1）：命令可为逻辑名（java/node/py/python/go）或绝对路径
     * （路径尾段文件名必须恰为探测面白名单可执行文件，如 {@code C:\Windows\py.exe}），
     * 参数规则与装配面一致；任一不合法抛 {@code 403002 COMMAND_REJECTED}。
     */
    public static void validateProbe(CommandSpec spec) {
        Objects.requireNonNull(spec, "spec 不能为 null");
        String command = spec.getCommand();
        if (resolveProbeExecutable(command) == null) {
            throw rejected("命令不在探测面白名单: " + command, command, null);
        }
        validateArgs(spec, command);
    }

    /**
     * 解析探测面命令为实际可执行串（R30 + P0-1）：逻辑名映射（{@code go → go.exe}）；
     * 绝对路径按尾段文件名白名单放行（P0-1 绑绝对路径，杜绝 PATH 首命中错配）。
     * 不在白名单返回 null。
     */
    public static String resolveProbeExecutable(String command) {
        if (command == null) {
            return null;
        }
        String mapped = PROBE_EXECUTABLE_BY_COMMAND.get(command);
        if (mapped != null) {
            return mapped;
        }
        return PROBE_EXECUTABLE_BY_COMMAND.containsValue(executableName(command)) ? command : null;
    }

    /** 路径尾段文件名（小写；Windows 不区分大小写），如 {@code C:\Windows\py.exe → py.exe}。 */
    private static String executableName(String command) {
        int sep = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        String name = sep >= 0 ? command.substring(sep + 1) : command;
        return name.toLowerCase(Locale.ROOT);
    }

    private static void validateArgs(CommandSpec spec, String command) {
        List<String> args = spec.getArgs() == null ? List.of() : spec.getArgs();
        if (args.size() > MAX_ARGS) {
            throw rejected("参数数量超过上限(" + MAX_ARGS + ")", command, null);
        }
        for (String arg : args) {
            String reason = denyReason(arg);
            if (reason != null) {
                throw rejected(reason, command, arg);
            }
        }
    }

    private static TerraScoutException rejected(String reason, String command, String arg) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        if (command != null) {
            details.put("command", command);
        }
        if (arg != null) {
            details.put("arg", arg);
        }
        return new TerraScoutException(TerraScoutError.COMMAND_REJECTED, details);
    }
}
