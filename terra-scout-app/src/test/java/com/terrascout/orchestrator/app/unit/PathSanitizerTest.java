package com.terrascout.orchestrator.app.unit;

import com.terrascout.orchestrator.app.util.PathSanitizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路径脱敏单测（security.md 6.10 安全红线 7：保留最后 2 级，前面替换为 ***）。
 */
class PathSanitizerTest {

    @Test
    void longPathKeepsLastTwoLevels() {
        assertThat(PathSanitizer.mask("C:\\Users\\user\\.terrascout\\db\\terrascout.mv.db"))
                .isEqualTo("***/db/terrascout.mv.db");
    }

    @Test
    void shortPathUnchanged() {
        assertThat(PathSanitizer.mask("foo/bar")).isEqualTo("foo/bar");
        assertThat(PathSanitizer.mask("file")).isEqualTo("file");
    }

    @Test
    void windowsBackslashNormalized() {
        assertThat(PathSanitizer.mask("D:\\ws\\proj\\node_modules\\x"))
                .isEqualTo("***/node_modules/x");
    }

    @Test
    void nullAndBlankReturnedAsIs() {
        assertThat(PathSanitizer.mask(null)).isNull();
        assertThat(PathSanitizer.mask("  ")).isEqualTo("  ");
    }
}
