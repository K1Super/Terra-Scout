package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.terrascout.orchestrator.core.constant.PathConstants;

import org.springframework.stereotype.Service;

/**
 * 数据库备份服务（process-management.md 5.9 / master-plan D-003）。
 *
 * <p>把当前 H2 文件库拷贝到 {@code {data-root}/db/backup/}，命名带时间戳，
 * 仅保留最近 {@value #KEEP_DAYS} 天（每次备份后清理过期备份）。
 */
@Service
public class BackupService {

    /** 备份保留天数（process-management 5.9：备份保留 7 天）。 */
    static final int KEEP_DAYS = 7;

    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 立即备份当前数据库文件，返回备份文件路径。 */
    public Path backup() {
        Path dataRoot = PathConstants.dataRoot();
        Path source = PathConstants.databaseFile(dataRoot);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("数据库文件不存在，无法备份");
        }
        String timestamp = LocalDateTime.now().format(BACKUP_TS);
        Path target = PathConstants.backupFile(dataRoot, timestamp);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new IllegalStateException("数据库备份失败", e);
        }
        prune(PathConstants.backupDir(dataRoot));
        return target;
    }

    /** 清理超过保留期的备份文件。 */
    private void prune(Path backupDir) {
        if (!Files.isDirectory(backupDir)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L;
        try (Stream<Path> stream = Files.list(backupDir)) {
            List<Path> expired = stream
                    .filter(p -> Files.isRegularFile(p) && lastModifiedOrZero(p) < cutoff)
                    .collect(Collectors.toList());
            for (Path path : expired) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // 清理失败不影响本次备份结果
        }
    }

    private static long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
