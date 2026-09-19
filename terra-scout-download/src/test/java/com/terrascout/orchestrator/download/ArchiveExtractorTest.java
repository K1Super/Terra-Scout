package com.terrascout.orchestrator.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * {@link ArchiveExtractor} 测试（四重安全检查；Zip-Slip → 422010）。
 */
class ArchiveExtractorTest {

    @TempDir
    Path workDir;

    private final ArchiveExtractor extractor = new ArchiveExtractor();

    @Test
    void extractsNormalZipPreservingStructure() throws Exception {
        Path archive = writeZip(workDir.resolve("a.zip"), mapOf(
                "a.txt", "hello",
                "sub/b.txt", "world"));
        Path target = workDir.resolve("out");

        extractor.extract(archive, target);

        assertThat(Files.readString(target.resolve("a.txt"), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(Files.readString(target.resolve("sub/b.txt"), StandardCharsets.UTF_8)).isEqualTo("world");
    }

    @Test
    void zipSlipEntryYields422010() throws Exception {
        Path archive = writeZip(workDir.resolve("evil.zip"), mapOf("../../escape.txt", "evil"));
        Path target = workDir.resolve("out");

        assertThatThrownBy(() -> extractor.extract(archive, target))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.ARCHIVE_UNSAFE);
        assertThat(target).doesNotExist();
    }

    @Test
    void invalidArchiveYields422010() throws IOException {
        Path archive = Files.writeString(workDir.resolve("bad.zip"), "this is not a zip",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(archive, workDir.resolve("out")))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.ARCHIVE_UNSAFE);
    }

    @Test
    void dangerousEntryNamePredicate() {
        assertThat(ArchiveExtractor.isDangerousEntryName("../../evil")).isTrue();
        assertThat(ArchiveExtractor.isDangerousEntryName("a/../b")).isTrue();
        assertThat(ArchiveExtractor.isDangerousEntryName("a\\..\\b")).isTrue();
        assertThat(ArchiveExtractor.isDangerousEntryName("/absolute")).isTrue();
        assertThat(ArchiveExtractor.isDangerousEntryName("C:/win")).isTrue();

        assertThat(ArchiveExtractor.isDangerousEntryName("normal.txt")).isFalse();
        assertThat(ArchiveExtractor.isDangerousEntryName("sub/b.txt")).isFalse();
        assertThat(ArchiveExtractor.isDangerousEntryName("")).isFalse();
        assertThat(ArchiveExtractor.isDangerousEntryName(null)).isFalse();
    }

    @Test
    void specialFileTypePredicate() {
        assertThat(ArchiveExtractor.isSpecialFileType(0x8000)).isFalse(); // 普通文件
        assertThat(ArchiveExtractor.isSpecialFileType(0x4000)).isFalse(); // 目录
        assertThat(ArchiveExtractor.isSpecialFileType(0)).isFalse();      // 未知
        assertThat(ArchiveExtractor.isSpecialFileType(0xA000)).isTrue();  // 符号链接
        assertThat(ArchiveExtractor.isSpecialFileType(0x1000)).isTrue();  // FIFO
    }

    @Test
    void unixTypeFromExternalAttributes() {
        // 4 字节小端；unix 模式在高 16 位，类型占 0xF000
        assertThat(ArchiveExtractor.unixType(new byte[]{0x00, 0x00, (byte) 0xA4, (byte) 0x81})).isEqualTo(0x8000); // 普通文件
        assertThat(ArchiveExtractor.unixType(new byte[]{0x00, 0x00, (byte) 0xFF, (byte) 0xA1})).isEqualTo(0xA000); // 符号链接
        assertThat(ArchiveExtractor.unixType(new byte[]{0x00, 0x00, (byte) 0xED, (byte) 0x41})).isEqualTo(0x4000); // 目录
        assertThat(ArchiveExtractor.unixType(null)).isZero();
        assertThat(ArchiveExtractor.unixType(new byte[]{0x01, 0x02})).isZero(); // 长度不足
    }

    @Test
    void limitConstantsEnforced() {
        assertThat(ArchiveExtractor.MAX_ENTRY_COUNT).isEqualTo(100_000L);
        assertThat(ArchiveExtractor.MAX_ENTRY_SIZE).isEqualTo(1L << 30);
    }

    // ---- 工具 ------------------------------------------------------------

    private static Map<String, byte[]> mapOf(String... keysAndValues) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            result.put(keysAndValues[i], keysAndValues[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Path writeZip(Path zipPath, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipParameters parameters = new ZipParameters();
                parameters.setFileNameInZip(e.getKey());
                zos.putNextEntry(parameters);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return zipPath;
    }
}
