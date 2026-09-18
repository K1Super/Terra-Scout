package com.terrascout.orchestrator.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * env.ps1 生成器（rest-schema 3.4.13，接受 TC-005 dot-source {@code . .\.devenv\env.ps1}）。
 *
 * <p>为当前 PowerShell 会话设置注入的环境变量（{@code $env:KEY='value'}），PATH 变量统一写
 * {@code $env:Path=...}。值内单引号按 PowerShell 单引号转义（{@code ''}）。
 * 写文件失败抛 {@code 500002 ENV_SCRIPT_FAILED}。
 */
public final class EnvScriptGenerator {

    /** PATH 变量在脚本中的 PowerShell 键。 */
    static final String PS_PATH_KEY = "Path";

    /**
     * 生成 env.ps1 脚本内容。
     *
     * @param env 待注入环境变量（由 {@link EnvInjector} 计算）
     * @return PowerShell 脚本文本
     */
    public String generate(Map<String, String> env) {
        Objects.requireNonNull(env, "env 不能为 null");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : singleQuoteEscape(e.getValue());
            String psKey = key.equalsIgnoreCase(EnvInjector.PATH_VAR) ? PS_PATH_KEY : key;
            sb.append("$env:").append(psKey).append(" = '").append(value).append("'\n");
        }
        return sb.toString();
    }

    /**
     * 生成并写入 env.ps1。
     *
     * @param scriptPath 目标路径（通常为 {@code PathConstants.isolationEnvScript(projectRoot)}）
     * @param env        待注入环境变量
     * @return scriptPath
     */
    public Path write(Path scriptPath, Map<String, String> env) {
        Objects.requireNonNull(scriptPath, "scriptPath 不能为 null");
        try {
            if (scriptPath.getParent() != null) {
                Files.createDirectories(scriptPath.getParent());
            }
            Files.writeString(scriptPath, generate(env), StandardCharsets.UTF_8);
            return scriptPath;
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.ENV_SCRIPT_FAILED,
                    "env.ps1 生成失败: " + scriptPath, e);
        }
    }

    private static String singleQuoteEscape(String value) {
        return value.replace("'", "''");
    }
}
