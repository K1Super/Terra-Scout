package com.terrascout.orchestrator.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * env.ps1 生成测试：可 dot-source。
 */
class EnvScriptGeneratorTest {

    private final EnvScriptGenerator generator = new EnvScriptGenerator();

    @TempDir
    Path tmp;

    @Test
    void generateEmitsSetPathStatements() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", "D:\\jdk\\17");
        env.put(EnvInjector.PATH_VAR, "D:\\jdk\\17\\bin;C:\\Windows");

        String script = generator.generate(env);

        assertThat(script).contains("$env:JAVA_HOME = 'D:\\jdk\\17'");
        // PATH 统一写 $env:Path
        assertThat(script).contains("$env:Path = 'D:\\jdk\\17\\bin;C:\\Windows'");
        assertThat(script).doesNotContain("$env:PATH");
    }

    @Test
    void singleQuoteEscapedInValue() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("WEIRD", "it's a 'value'");
        String script = generator.generate(env);
        assertThat(script).contains("$env:WEIRD = 'it''s a ''value'''");
    }

    @Test
    void writeCreatesParentDirsAndWritesContent() throws Exception {
        Path script = tmp.resolve("proj").resolve(".devenv").resolve("env.ps1");
        Map<String, String> env = Map.of("JAVA_HOME", "D:\\jdk\\17");
        Path written = generator.write(script, env);

        assertThat(written).isEqualTo(script);
        assertThat(Files.exists(script)).isTrue();
        assertThat(Files.readString(script)).contains("$env:JAVA_HOME = 'D:\\jdk\\17'");
    }

    @Test
    void writeToInvalidParentThrows500002() throws Exception {
        // 父路径是一个普通文件 → createDirectories 抛 FileAlreadyExistsException
        Path blocker = Files.writeString(tmp.resolve("blocker"), "x");
        Path badScript = blocker.resolve("env.ps1");

        assertThatThrownBy(() -> generator.write(badScript, Map.of("K", "V")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> assertThat(((TerraScoutException) e).getError())
                        .isEqualTo(TerraScoutError.ENV_SCRIPT_FAILED));
    }

    @Test
    void nullParametersRejected() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.write(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullValueEmittedEmpty() throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("KEY", null);
        assertThat(generator.generate(env)).contains("$env:KEY = ''");
    }
}
