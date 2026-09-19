package com.terrascout.orchestrator.core.dto;

import java.util.List;

/**
 * 命令规格。
 *
 * <p>命令执行铁律：命令必须过白名单（mvn.cmd / npm.cmd / java.exe / node.exe），
 * 参数逐个匹配白名单正则 {@code ^[A-Za-z0-9@+=:,._/\\-]+$}，禁止字符串拼接构造命令行。
 * {@code command} 取值：{@code mvn} / {@code npm} / {@code node} / {@code java}（小写）。
 */
public class CommandSpec {

    /** 命令名（mvn / npm / node / java，小写；实际执行映射为白名单内 .cmd/.exe）。 */
    private String command;

    /** 参数列表（每项 ≤ 1024 字符，最多 64 项）。 */
    private List<String> args;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }
}
