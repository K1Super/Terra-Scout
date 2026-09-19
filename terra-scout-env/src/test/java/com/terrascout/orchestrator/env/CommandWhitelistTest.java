package com.terrascout.orchestrator.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.junit.jupiter.api.Test;

/**
 * 命令/参数白名单测试：命令注入拦截。
 */
class CommandWhitelistTest {

    private static CommandSpec spec(String cmd, String... args) {
        CommandSpec s = new CommandSpec();
        s.setCommand(cmd);
        s.setArgs(List.of(args));
        return s;
    }

    @Test
    void allowedCommandResolvesToExecutable() {
        assertThat(CommandWhitelist.resolveExecutable("mvn")).isEqualTo("mvn.cmd");
        assertThat(CommandWhitelist.resolveExecutable("npm")).isEqualTo("npm.cmd");
        assertThat(CommandWhitelist.resolveExecutable("java")).isEqualTo("java.exe");
        assertThat(CommandWhitelist.resolveExecutable("node")).isEqualTo("node.exe");
        assertThat(CommandWhitelist.resolveExecutable("go")).isEqualTo("go.exe");
        assertThat(CommandWhitelist.resolveExecutable("pip")).isEqualTo("pip.exe");
        assertThat(CommandWhitelist.resolveExecutable("python")).isEqualTo("python.exe");
    }

    @Test
    void unknownCommandRejected() {
        assertThat(CommandWhitelist.isAllowedCommand("rm")).isFalse();
        assertThat(CommandWhitelist.resolveExecutable("sh")).isNull();
        assertThatThrownBy(() -> CommandWhitelist.validate(spec("rm", "-rf")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
    }

    @Test
    void nullCommandRejected() {
        assertThat(CommandWhitelist.isAllowedCommand(null)).isFalse();
        assertThatThrownBy(() -> CommandWhitelist.validate(spec(null, "x")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
    }

    @Test
    void tc010InjectionMetacharactersRejected() {
        // cmd.exe 全部元字符：& | ; > < ` $ % ^ ( ) ! " '
        String[] metas = {"&", "|", ";", ">", "<", "`", "$", "%", "^", "(", ")", "!", "\"", "'"};
        for (String meta : metas) {
            assertThat(CommandWhitelist.isAllowedArg(meta))
                    .as("元字符应被拒绝: '" + meta + "'")
                    .isFalse();
        }
        // "mvn; rm -rf /" 注入片段
        assertThatThrownBy(() -> CommandWhitelist.validate(spec("mvn", "-version; rm -rf /")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
    }

    @Test
    void safeArgsAllowed() {
        assertThat(CommandWhitelist.isAllowedArg("-version")).isTrue();
        assertThat(CommandWhitelist.isAllowedArg("-Dmaven.test.skip=true")).isTrue();
        assertThat(CommandWhitelist.isAllowedArg("com.example:artifact:1.0.0")).isTrue();
        assertThat(CommandWhitelist.isAllowedArg("C:/path/to/file")).isTrue();
        assertThat(CommandWhitelist.isAllowedArg("a@:+=_,.-\\")).isTrue();
        CommandWhitelist.validate(spec("mvn", "-version")); // 合法调用不抛
    }

    @Test
    void nullArgRejected() {
        assertThat(CommandWhitelist.isAllowedArg(null)).isFalse();
        assertThat(CommandWhitelist.denyReason(null)).isNotNull();
    }

    @Test
    void argTooLongRejected() {
        String longArg = "a".repeat(CommandWhitelist.MAX_ARG_LENGTH + 1);
        assertThat(CommandWhitelist.isAllowedArg(longArg)).isFalse();
        assertThatThrownBy(() -> CommandWhitelist.validate(spec("mvn", longArg)))
                .isInstanceOf(TerraScoutException.class);
    }

    @Test
    void tooManyArgsRejected() {
        String[] many = new String[CommandWhitelist.MAX_ARGS + 1];
        java.util.Arrays.fill(many, "x");
        assertThatThrownBy(() -> CommandWhitelist.validate(spec("mvn", many)))
                .isInstanceOf(TerraScoutException.class);
    }

    @Test
    void nullSpecRejected() {
        assertThatThrownBy(() -> CommandWhitelist.validate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullArgsTreatedAsEmpty() {
        CommandSpec s = new CommandSpec();
        s.setCommand("mvn");
        s.setArgs(null);
        CommandWhitelist.validate(s); // 不抛
    }

    // ── 探测面 ────────────────────────────────────────────────

    @Test
    void probeSurfaceAllowsReadOnlySdks() {
        for (String command : List.of("java", "node", "py", "python", "go")) {
            assertThat(CommandWhitelist.isAllowedCommand(command, CommandWhitelist.Surface.PROBE))
                    .as("探测面应允许: " + command)
                    .isTrue();
        }
        assertThat(CommandWhitelist.resolveExecutable("py", CommandWhitelist.Surface.PROBE))
                .isEqualTo("py.exe");
        assertThat(CommandWhitelist.resolveExecutable("python", CommandWhitelist.Surface.PROBE))
                .isEqualTo("python.exe");
        assertThat(CommandWhitelist.resolveExecutable("go", CommandWhitelist.Surface.PROBE))
                .isEqualTo("go.exe");
        CommandWhitelist.validate(spec("py", "-0p"), CommandWhitelist.Surface.PROBE); // 不抛
        CommandWhitelist.validate(spec("go", "version"), CommandWhitelist.Surface.PROBE); // 不抛
    }

    @Test
    void onlyPyRemainsProbeExclusiveOnAssemblySurface() {
        // go/pip/python 已放行装配面（Go/Python 全链路装配），探测面专属仅剩 py（Python Launcher）
        assertThat(CommandWhitelist.isAllowedCommand("py")).isFalse();
        assertThat(CommandWhitelist.resolveExecutable("py")).isNull();
        assertThatThrownBy(() -> CommandWhitelist.validate(spec("py", "-0p")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
        // 装配面不放宽探测面
        assertThat(CommandWhitelist.isAllowedCommand("go")).isTrue();
        assertThat(CommandWhitelist.isAllowedCommand("python")).isTrue();
        assertThat(CommandWhitelist.isAllowedCommand("pip")).isTrue();
        assertThat(CommandWhitelist.resolveExecutable("go")).isEqualTo("go.exe");
        // 装配面命令不在探测面
        assertThat(CommandWhitelist.isAllowedCommand("mvn", CommandWhitelist.Surface.PROBE)).isFalse();
        assertThat(CommandWhitelist.isAllowedCommand("npm", CommandWhitelist.Surface.PROBE)).isFalse();
    }

    @Test
    void probeSurfaceStillEnforcesArgSafety() {
        assertThatThrownBy(() -> CommandWhitelist.validate(
                spec("python", "-c", "import os;os.system('x')"), CommandWhitelist.Surface.PROBE))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
        assertThatThrownBy(() -> CommandWhitelist.validate(
                spec("go", "version; rm -rf /"), CommandWhitelist.Surface.PROBE))
                .isInstanceOf(TerraScoutException.class);
    }

    // ── 探测面绝对路径 ───────────────────────────────────────

    @Test
    void probeSurfaceAcceptsAbsolutePathExecutable() {
        // 探测面命令可绑绝对路径,放行条件 = 路径尾段文件名恰为白名单可执行
        for (String abs : List.of("C:\\Windows\\py.exe",
                "D:\\Deps\\Nodejs\\node.exe",
                "C:\\Program Files\\Go\\bin\\go.exe",
                "C:/Python311/python.exe")) {
            CommandWhitelist.validateProbe(spec(abs, "-0p")); // 不抛即放行
        }
        assertThat(CommandWhitelist.resolveProbeExecutable("C:\\Windows\\py.exe"))
                .isEqualTo("C:\\Windows\\py.exe");
    }

    @Test
    void probeSurfaceRejectsUnknownExecutableFileName() {
        // 尾段文件名不在白名单(calc/cmd/伪 java.exe.bat)一律 403002
        for (String abs : List.of("C:\\Windows\\System32\\calc.exe",
                "C:\\Windows\\System32\\cmd.exe",
                "C:\\evil\\java.exe.bat",
                "C:\\evil\\py.exe\\..\\..\\calc.exe")) {
            assertThatThrownBy(() -> CommandWhitelist.validateProbe(spec(abs, "-version")))
                    .as("陌生可执行应被拒绝: " + abs)
                    .isInstanceOf(TerraScoutException.class)
                    .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                            .isEqualTo(TerraScoutError.COMMAND_REJECTED));
        }
        assertThat(CommandWhitelist.resolveProbeExecutable("C:\\Windows\\System32\\calc.exe")).isNull();
    }

    @Test
    void probeSurfaceAbsolutePathStillEnforcesArgSafety() {
        // 绝对路径放行命令后,参数校验强度与逻辑名路径完全一致
        assertThatThrownBy(() -> CommandWhitelist.validateProbe(
                spec("C:\\Windows\\py.exe", "-c", "os.system('x')")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
        assertThatThrownBy(() -> CommandWhitelist.validateProbe(
                spec("D:\\Deps\\Go\\bin\\go.exe", "version; rm -rf /")))
                .isInstanceOf(TerraScoutException.class);
    }

    @Test
    void probeSurfaceExecutableNameCaseInsensitive() {
        // Windows 文件系统不区分大小写
        CommandWhitelist.validateProbe(spec("D:\\Deps\\Python311\\PYTHON.EXE", "--version")); // 不抛
        assertThat(CommandWhitelist.resolveProbeExecutable("C:\\Windows\\Py.Exe"))
                .isEqualTo("C:\\Windows\\Py.Exe");
    }

    @Test
    void validateProbeNullCommandRejectedWith403002() {
        CommandSpec s = new CommandSpec();
        s.setCommand(null);
        s.setArgs(null);
        assertThatThrownBy(() -> CommandWhitelist.validateProbe(s))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.COMMAND_REJECTED));
    }
}
