package com.terrascout.orchestrator.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 环境注入计算测试：注入正确，不污染系统全局。
 */
class EnvInjectorTest {

    private final EnvInjector injector = new EnvInjector();

    @TempDir
    Path tmp;

    private Path javaHome() throws Exception {
        Path home = tmp.resolve("jdk-home");
        Files.createDirectories(home.resolve("bin"));
        return home;
    }

    private Path nodeHome() throws Exception {
        return Files.createDirectories(tmp.resolve("node-home"));
    }

    private Path goHome() throws Exception {
        // Go 官方 zip 解压后为 <home>\go 布局
        Path home = tmp.resolve("go-home");
        Files.createDirectories(home.resolve("go").resolve("bin"));
        return home;
    }

    private Path pythonHome() throws Exception {
        // Windows embeddable 版 python.exe 位于根目录
        return Files.createDirectories(tmp.resolve("python-home"));
    }

    @Test
    void javaAndNodeHomesInjectedWithPathPrepend() throws Exception {
        Path jh = javaHome();
        Path nh = nodeHome();
        Map<String, String> env = injector.buildEnv(tmp, Map.of("java", jh.toString(), "node", nh.toString()),
                "C:\\Windows\\System32");

        assertThat(env).containsEntry(EnvInjector.JAVA_HOME_VAR, jh.toString());
        assertThat(env).containsEntry(EnvInjector.NODE_HOME_VAR, nh.toString());
        assertThat(env.get(EnvInjector.PATH_VAR)).startsWith(jh.resolve("bin").toString()).contains(nh.toString());
        assertThat(env.get(EnvInjector.PATH_VAR)).endsWith("C:\\Windows\\System32");
    }

    @Test
    void mavenIsolationAndNpmCacheSet() throws Exception {
        Map<String, String> env = injector.buildEnv(tmp, Map.of("java", javaHome().toString()), "");
        assertThat(env.get(EnvInjector.MAVEN_OPTS_VAR))
                .isEqualTo("-Dmaven.repo.local=" + tmp.resolve(".devenv").resolve("m2"));
        assertThat(env.get(EnvInjector.NPM_CONFIG_CACHE_VAR))
                .isEqualTo(tmp.resolve(".devenv").resolve("npm-cache").toString());
    }

    @Test
    void goHomeInjectedWithGorootAndBinPrepend() throws Exception {
        Path gh = goHome();
        Map<String, String> env = injector.buildEnv(tmp, Map.of("go", gh.toString()),
                "C:\\Windows\\System32");

        assertThat(env).containsEntry(EnvInjector.GOROOT_VAR, gh.resolve("go").toString());
        assertThat(env).containsEntry(EnvInjector.GOMODCACHE_VAR,
                tmp.resolve(".devenv").resolve("go-cache").toString());
        assertThat(env.get(EnvInjector.PATH_VAR))
                .startsWith(gh.resolve("go").resolve("bin").toString())
                .endsWith("C:\\Windows\\System32");
    }

    @Test
    void pythonHomePrependedToPath() throws Exception {
        Path ph = pythonHome();
        Map<String, String> env = injector.buildEnv(tmp, Map.of("python", ph.toString()),
                "C:\\Windows\\System32");
        assertThat(env.get(EnvInjector.PATH_VAR))
                .startsWith(ph.toString())
                .endsWith("C:\\Windows\\System32");
    }

    @Test
    void missingGoHomeThrows422014() throws Exception {
        Path phantom = tmp.resolve("no-such-go-home");
        assertThatThrownBy(() -> injector.buildEnv(tmp, Map.of("go", phantom.toString()), ""))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.HOME_PATH_INVALID));
    }

    @Test
    void missingJavaHomeThrows422014() throws Exception {
        Path phantom = tmp.resolve("no-such-home");
        assertThatThrownBy(() -> injector.buildEnv(tmp, Map.of("java", phantom.toString()), ""))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.HOME_PATH_INVALID));
    }

    @Test
    void blankInheritPathYieldsOnlyPrefix() throws Exception {
        Path jh = javaHome();
        Map<String, String> env = injector.buildEnv(tmp, Map.of("java", jh.toString()), "");
        assertThat(env.get(EnvInjector.PATH_VAR)).isEqualTo(jh.resolve("bin").toString());
    }

    @Test
    void noHomesStillReturnsIsolationAndPath() throws Exception {
        Map<String, String> env = injector.buildEnv(tmp, Map.of(), "C:\\Windows");
        assertThat(env.get(EnvInjector.PATH_VAR)).isEqualTo("C:\\Windows");
        assertThat(env).doesNotContainKey(EnvInjector.JAVA_HOME_VAR);
        assertThat(env.get(EnvInjector.MAVEN_OPTS_VAR)).isNotNull();
    }

    @Test
    void nullParametersRejected() {
        assertThatThrownBy(() -> injector.buildEnv(null, Map.of(), ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> injector.buildEnv(tmp, null, ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> injector.buildEnv(tmp, Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
