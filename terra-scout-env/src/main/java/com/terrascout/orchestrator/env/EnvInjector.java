package com.terrascout.orchestrator.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * 进程级环境注入计算（环境隔离域）。
 *
 * <p>生成子进程要注入的环境变量：JAVA_HOME / NODE_HOME / GOROOT / GOMODCACHE、前置到 PATH 的 bin 目录
 * （python 目标目录直置 PATH 首位，embeddable 版 python.exe 位于根目录）、
 * Maven 隔离本地仓库（{@code -Dmaven.repo.local}={isolation}/m2）、npm 隔离缓存
 * （{@code npm_config_cache}={isolation}/npm-cache）与 Go 模块缓存
 * （{@code GOMODCACHE}={isolation}/go-cache）。注入路径必须真实存在，否则抛
 * {@code 422014 HOME_PATH_INVALID}。
 */
public final class EnvInjector {

    public static final String JAVA_HOME_VAR = "JAVA_HOME";
    public static final String NODE_HOME_VAR = "NODE_HOME";
    public static final String GOROOT_VAR = "GOROOT";
    public static final String GOMODCACHE_VAR = "GOMODCACHE";
    public static final String PATH_VAR = "PATH";
    public static final String MAVEN_OPTS_VAR = "MAVEN_OPTS";
    public static final String NPM_CONFIG_CACHE_VAR = "npm_config_cache";

    private static final String PATH_SEPARATOR = System.getProperty("path.separator", ";");

    /**
     * 构建注入环境。sdkHomes 键为 {@code "java"} / {@code "node"} / {@code "go"} / {@code "python"}
     * （home 目录路径）。
     *
     * @param projectRoot 项目根（用于隔离域路径）
     * @param sdkHomes    SDK 主页映射（java/node/go/python）
     * @param inheritPath 继承的系统 PATH（用于在其前置 bin 目录）
     * @return 环境变量映射（含覆盖后的 PATH）
     */
    public Map<String, String> buildEnv(Path projectRoot, Map<String, String> sdkHomes, String inheritPath) {
        Objects.requireNonNull(projectRoot, "projectRoot 不能为 null");
        Objects.requireNonNull(sdkHomes, "sdkHomes 不能为 null");
        Objects.requireNonNull(inheritPath, "inheritPath 不能为 null");

        Map<String, String> env = new LinkedHashMap<>();
        List<String> pathPrefix = new ArrayList<>();
        String javaHome = sdkHomes.get("java");
        if (isPresent(javaHome)) {
            requireHome(javaHome, "JAVA_HOME");
            env.put(JAVA_HOME_VAR, javaHome);
            pathPrefix.add(Path.of(javaHome).resolve("bin").toString());
        }
        String nodeHome = sdkHomes.get("node");
        if (isPresent(nodeHome)) {
            requireHome(nodeHome, "NODE_HOME");
            env.put(NODE_HOME_VAR, nodeHome);
            pathPrefix.add(nodeHome);
        }
        String goHome = sdkHomes.get("go");
        if (isPresent(goHome)) {
            requireHome(goHome, "GOROOT");
            // Go 官方 zip 顶层为 go/ 目录：GOROOT = <home>\go，可执行位于 <home>\go\bin
            Path goRoot = Path.of(goHome).resolve("go");
            env.put(GOROOT_VAR, goRoot.toString());
            pathPrefix.add(goRoot.resolve("bin").toString());
        }
        // Windows embeddable 版 python.exe 位于根目录，直接前置 home 目录
        String pythonHome = sdkHomes.get("python");
        if (isPresent(pythonHome)) {
            requireHome(pythonHome, "PYTHON_HOME");
            pathPrefix.add(pythonHome);
        }

        env.put(MAVEN_OPTS_VAR, "-Dmaven.repo.local=" + PathConstants.isolationM2(projectRoot));
        env.put(NPM_CONFIG_CACHE_VAR, PathConstants.isolationNpmCache(projectRoot).toString());
        env.put(GOMODCACHE_VAR, PathConstants.isolationGoCache(projectRoot).toString());

        String prefix = String.join(PATH_SEPARATOR, pathPrefix);
        env.put(PATH_VAR, prefix.isBlank()
                ? inheritPath
                : inheritPath.isBlank() ? prefix : prefix + PATH_SEPARATOR + inheritPath);
        return env;
    }

    /** 便捷重载：继承系统 PATH。 */
    public Map<String, String> buildEnv(Path projectRoot, Map<String, String> sdkHomes) {
        String inherit = System.getenv(PATH_VAR);
        return buildEnv(projectRoot, sdkHomes, inherit == null ? "" : inherit);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireHome(String home, String var) {
        if (!Files.isDirectory(Path.of(home))) {
            throw new TerraScoutException(TerraScoutError.HOME_PATH_INVALID,
                    "注入后 " + var + " 路径不存在: " + home);
        }
    }
}
