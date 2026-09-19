package com.terrascout.orchestrator.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * 归档解压器（Zip4j；四重安全检查，解压中止 → 422010 ARCHIVE_UNSAFE）。
 *
 * <p>四重检查：Zip-Slip 路径穿越、符号/硬链接与设备文件、单文件解压后大小、文件总数。
 * 另做解压目标磁盘空间校验（不足 → 507002 EXTRACT_DISK_FULL）。
 */
public final class ArchiveExtractor {

    /** 解压文件总数上限。 */
    public static final long MAX_ENTRY_COUNT = 100_000L;

    /** 单文件解压后大小上限：1GB。 */
    public static final long MAX_ENTRY_SIZE = 1L << 30;

    /** Unix 文件类型掩码所在位。 */
    private static final int TYPE_MASK = 0xF000;

    private static final int REGULAR = 0x8000;
    private static final int DIRECTORY = 0x4000;

    /** 提取的目标目录（复用同一次调用，可复用）。 */
    public void extract(Path archive, Path targetDir) {
        Objects.requireNonNull(archive, "archive 不能为 null");
        Objects.requireNonNull(targetDir, "targetDir 不能为 null");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<FileHeader> headers = zip.getFileHeaders();
            long totalUncompressed = validateAll(headers);
            checkExtractDiskSpace(targetDir, totalUncompressed);
            Files.createDirectories(targetDir);
            zip.extractAll(targetDir.toString());
        } catch (ZipException e) {
            throw new TerraScoutException(TerraScoutError.ARCHIVE_UNSAFE,
                    "解压失败或归档结构异常: " + archive, e);
        } catch (IOException e) {
            throw new IllegalStateException("创建解压目录失败: " + targetDir, e);
        }
    }

    /**
     * 四重检查并累计解压目标总字节数。任一违反抛 422010。
     */
    private static long validateAll(List<FileHeader> headers) {
        if (headers.size() > MAX_ENTRY_COUNT) {
            throw unsafe("归档文件数超过上限 " + MAX_ENTRY_COUNT);
        }
        long total = 0L;
        for (FileHeader header : headers) {
            String name = header.getFileName();
            if (isDangerousEntryName(name)) {
                throw unsafe("检测到路径穿越（Zip-Slip）: " + name);
            }
            int unixType = unixType(header.getExternalFileAttributes());
            if (isSpecialFileType(unixType)) {
                throw unsafe("检测到符号/硬链接或设备文件: " + name);
            }
            boolean directory = header.isDirectory();
            if (!directory && header.getUncompressedSize() > MAX_ENTRY_SIZE) {
                throw unsafe("单文件解压后超过 1GB: " + name);
            }
            if (!directory) {
                total += header.getUncompressedSize();
            }
        }
        return total;
    }

    /**
     * Zip-Slip 判定（包私有，便于单测直测）：
     * 绝对路径，或任一路径段为 {@code ..}（含可逃逸上级目录的分段）。
     *
     * @return true 表示存在穿越风险
     */
    static boolean isDangerousEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return false;
        }
        String clean = entryName.replace('\\', '/');
        if (clean.startsWith("/")) {
            return true;
        }
        boolean absoluteWindows = clean.length() >= 2 && clean.charAt(1) == ':';
        if (absoluteWindows) {
            return true;
        }
        for (String segment : clean.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 external file attributes（4 字节小端）提取 unix 文件类型位。
     * Zip4j 2.11.5 该字段为 {@code byte[]}；unix 模式占据高 16 位，类型掩码 0xF000 在此区间。
     * 属性缺失或长度不足视为未知(0)，不判定为特殊文件。
     *
     * @param attrs Zip4j 返回的外部属性字节数组，可为 null
     * @return unix 类型位（已按 TYPE_MASK 归一），属性缺失时返回 0
     */
    static int unixType(byte[] attrs) {
        if (attrs == null || attrs.length < 4) {
            return 0;
        }
        int upper16 = ((attrs[3] & 0xFF) << 8) | (attrs[2] & 0xFF);
        return upper16 & TYPE_MASK;
    }

    /**
     * 特殊文件类型判定（包私有，便于单测直测）：unixMode 为未知(0)、普通文件(0x8000)、目录(0x4000) 之外
     * 的符号链接(0xA000)、字符/块设备、管道、套接字均视为危险。
     *
     * @return true 表示非普通文件/目录
     */
    static boolean isSpecialFileType(int unixMode) {
        return unixMode != 0 && unixMode != REGULAR && unixMode != DIRECTORY;
    }

    /**
     * 解压目标磁盘空间校验：可用 &lt; 解压总量抛 507002。
     * 目标目录可能尚未创建（校验先于 createDirectories），故向上回溯到最近已存在的祖先取磁盘状态。
     */
    private static void checkExtractDiskSpace(Path targetDir, long total) {
        if (total <= 0L) {
            return;
        }
        long usable;
        try {
            usable = Files.getFileStore(nearestExistingAncestor(targetDir)).getUsableSpace();
        } catch (IOException e) {
            throw new IllegalStateException("获取磁盘可用空间失败: " + targetDir, e);
        }
        if (usable < total) {
            throw new TerraScoutException(TerraScoutError.EXTRACT_DISK_FULL,
                    "解压目标磁盘空间不足: 需 " + total + " 字节，可用 " + usable);
        }
    }

    /**
     * 在 targetDir 尚不存在时回溯到最近已存在的父目录，保证 getFileStore 可用。
     */
    private static Path nearestExistingAncestor(Path targetDir) {
        Path anchor = targetDir;
        while (!Files.exists(anchor)) {
            Path parent = anchor.getParent();
            if (parent == null) {
                throw new IllegalStateException("目标目录未包含有效的可校验磁盘根: " + targetDir);
            }
            anchor = parent;
        }
        return anchor;
    }

    private static TerraScoutException unsafe(String reason) {
        return new TerraScoutException(TerraScoutError.ARCHIVE_UNSAFE, "解压中止: " + reason);
    }
}
