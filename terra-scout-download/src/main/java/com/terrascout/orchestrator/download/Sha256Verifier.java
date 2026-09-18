package com.terrascout.orchestrator.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * SHA-256 校验工具（security.md 6.6；校验失败 → 422009 CHECKSUM_MISMATCH）。
 *
 * <p>输入为 64 位十六进制摘要字符串；判定大小写不敏感；输入非 64 位十六进制视为调用约定错误（IAE）。
 */
public final class Sha256Verifier {

    /** 合法 SHA-256 十六进制摘要（大小写不敏感，64 位）。 */
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-fA-F]{64}$");

    private static final int BUFFER_SIZE = 8192;

    private Sha256Verifier() {
    }

    /**
     * 计算文件 SHA-256（小写十六进制）。
     *
     * @param file 目标文件
     * @return 64 位小写十六进制摘要
     * @throws NullPointerException file 为 null
     */
    public static String sha256(Path file) {
        Objects.requireNonNull(file, "file 不能为 null");
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("计算 SHA-256 失败: " + file, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 判定文件内容是否与期望摘要一致（大小写不敏感）。
     *
     * @param file        目标文件
     * @param expectedHex 期望的 64 位十六进制摘要
     * @return 一致返回 true
     */
    public static boolean matches(Path file, String expectedHex) {
        return sha256(file).equalsIgnoreCase(Objects.requireNonNull(expectedHex, "expectedHex 不能为 null"));
    }

    /**
     * 校验文件摘要；不一致抛 422009。
     *
     * @param file        目标文件
     * @param expectedHex 期望的 64 位十六进制摘要
     * @throws TerraScoutException 422009 读数摘要与期望不一致
     * @throws IllegalArgumentException expectedHex 非法（长度/字符不符）
     */
    public static void verify(Path file, String expectedHex) {
        Objects.requireNonNull(expectedHex, "expectedHex 不能为 null");
        if (!HEX64.matcher(expectedHex).matches()) {
            throw new IllegalArgumentException("非法的 SHA-256 摘要: " + expectedHex);
        }
        if (!matches(file, expectedHex)) {
            throw new TerraScoutException(TerraScoutError.CHECKSUM_MISMATCH,
                    "文件 SHA-256 校验失败: " + file);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 算法", e);
        }
    }
}
