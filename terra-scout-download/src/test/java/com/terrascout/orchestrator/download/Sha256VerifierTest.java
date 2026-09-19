package com.terrascout.orchestrator.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * {@link Sha256Verifier} 单元测试（校验失败 422009）。
 */
class Sha256VerifierTest {

    /** sha256("abc") 已知摘要。 */
    private static final String SHA_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path tempDir;

    private Path file(String content) throws IOException {
        Path p = tempDir.resolve(content.length() + ".txt");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void computesLowercaseHexDigestOfKnownValue() throws IOException {
        assertThat(Sha256Verifier.sha256(file("abc"))).isEqualTo(SHA_ABC);
    }

    @Test
    void matchesAcceptsUppercaseExpected() throws IOException {
        assertThat(Sha256Verifier.matches(file("abc"), SHA_ABC.toUpperCase())).isTrue();
    }

    @Test
    void verifySucceedsOnMatch() throws IOException {
        Path p = file("abc");
        Sha256Verifier.verify(p, SHA_ABC);
        assertThat(p).exists();
    }

    @Test
    void verifyThrowsCheckSumMismatchOnCorrupt() throws IOException {
        Path p = file("defg");
        assertThatThrownBy(() -> Sha256Verifier.verify(p, SHA_ABC))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.CHECKSUM_MISMATCH);
    }

    @Test
    void verifyThrowsIaeOnNonHexExpected() throws IOException {
        Path p = file("abc");
        assertThatThrownBy(() -> Sha256Verifier.verify(p, "zz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyThrowsIaeOnWrongLength() throws IOException {
        Path p = file("abc");
        assertThatThrownBy(() -> Sha256Verifier.verify(p, "abcd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullFileThrowsNpe() {
        assertThatThrownBy(() -> Sha256Verifier.sha256(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Sha256Verifier.matches(null, SHA_ABC))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullExpectedThrowsNpe() throws IOException {
        assertThatThrownBy(() -> Sha256Verifier.verify(file("abc"), null))
                .isInstanceOf(NullPointerException.class);
    }
}
