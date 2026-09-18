package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.enums.InstallStatusEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.ScopeEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.download.ArchiveExtractor;
import com.terrascout.orchestrator.download.Downloader;
import com.terrascout.orchestrator.download.ProgressListener;
import com.terrascout.orchestrator.download.ResumeableDownloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SDK 安装器：真实下载 + SHA-256 + 解压 + 写安装记录（D-004 全局仓库物理落位）。
 *
 * <p>幂等：同语言+版本已有 SUCCESS 记录直接复用；回滚仅删除本项目（PROJECT scope）记录与目录，
 * 避免误删全局共享 SDK。安装全程支持取消：监听方置取消信号后，安装器在下载块 /
 * 阶段边界中止，并清理下载暂存（.part）与半解压目录后再抛出
 * {@link CancellationException 取消异常}（由任务编排层收敛为 CANCELLED 终态）。
 */
@Service
public class SdkInstaller {

    private static final Logger LOGGER = LoggerFactory.getLogger(SdkInstaller.class);

    private final SdkInstallRecordRepository repository;
    private final Downloader downloader;
    private final ArchiveExtractor archiveExtractor;

    public SdkInstaller(SdkInstallRecordRepository repository,
                        Downloader downloader,
                        ArchiveExtractor archiveExtractor) {
        this.repository = repository;
        this.downloader = downloader;
        this.archiveExtractor = archiveExtractor;
    }

    /** 查找已有成功安装记录（跨项目复用语义）。 */
    public Optional<SdkInstallRecord> findInstalled(LanguageEnum language, String version) {
        return repository.findByLanguageAndVersionAndStatus(language, version, InstallStatusEnum.SUCCESS)
                .stream().findFirst();
    }

    /** 幂等安装：已装复用，否则下载 + 校验 + 解压 + 写记录，返回 SUCCESS 记录（无进度回调，标准仓库落位）。 */
    public SdkInstallRecord ensureInstalled(SdkVersion version, ScopeEnum scope, String projectId) {
        return ensureInstalled(version, scope, projectId, null, SdkInstallListener.NOOP);
    }

    /** 幂等安装：已装复用，否则下载 + 校验 + 解压 + 写记录；全程经 listener 上报阶段与下载进度，
     * 取消信号置位后在最近检查点中止并清理残留（.part 与半解压目录）。 */
    public SdkInstallRecord ensureInstalled(SdkVersion version, ScopeEnum scope, String projectId,
                                            SdkInstallListener listener) {
        return ensureInstalled(version, scope, projectId, null, listener);
    }

    /** 幂等安装（自定义落位）：customHome 非空时安装到用户所选目录（服务端已校验绝对路径与可用性），
     * null 时回落 D-004 标准仓库；成功解压后写落位票根文件，卸载物理删除凭此识别自装目录。 */
    public SdkInstallRecord ensureInstalled(SdkVersion version, ScopeEnum scope, String projectId,
                                            Path customHome, SdkInstallListener listener) {
        SdkInstallListener safeListener = listener == null ? SdkInstallListener.NOOP : listener;
        Optional<SdkInstallRecord> existing = findInstalled(version.getLanguage(), version.getVersion());
        if (existing.isPresent()) {
            safeListener.onStage("DONE", "该版本已安装，直接复用");
            return existing.get();
        }
        Path home = customHome != null
                ? customHome
                : PathConstants.sdkHome(PathConstants.dataRoot(), version.getLanguage(),
                        version.getVersion());
        Path dataRoot = PathConstants.dataRoot();
        Path archive = null;
        try {
            checkCancelled(safeListener);
            safeListener.onStage("STARTING", "校验安装环境");
            safeListener.onStage("DOWNLOADING", "正在下载 " + version.getLanguage() + " " + version.getVersion());
            archive = downloadArchive(version, dataRoot, safeListener);
            checkCancelled(safeListener);
            safeListener.onStage("EXTRACTING", "正在解压安装包");
            archiveExtractor.extract(archive, home);
            checkCancelled(safeListener);
        } catch (CancellationException e) {
            // 取消红线：剔除下载暂存与半解压残留，确保用户空间不被无名占用
            deleteQuietly(archive);
            deleteQuietly(partOf(archive));
            deleteRecursivelyQuietly(home);
            throw e;
        } finally {
            deleteQuietly(archive);
        }
        writeInstallMarker(home, version);
        safeListener.onStage("FINISHING", "正在写入安装记录");
        SdkInstallRecord record = new SdkInstallRecord();
        record.setId(UUID.randomUUID().toString());
        record.setLanguage(version.getLanguage());
        record.setVersion(version.getVersion());
        record.setInstallPath(home.toString());
        record.setScope(scope == null ? ScopeEnum.GLOBAL : scope);
        record.setProjectId(projectId);
        record.setStatus(InstallStatusEnum.SUCCESS);
        record.setChecksum(version.getSha256());
        record.setInstalledAt(System.currentTimeMillis());
        repository.save(record);
        safeListener.onStage("DONE", "安装完成");
        return record;
    }

    /** 写落位票根（language/version/home）：卸载物理删除的护栏凭据；失败仅告警不阻断安装。 */
    private static void writeInstallMarker(Path home, SdkVersion version) {
        try {
            Files.writeString(home.resolve(PathConstants.FILE_SDK_MARKER),
                    "language=" + version.getLanguage().name() + System.lineSeparator()
                            + "version=" + version.getVersion() + System.lineSeparator()
                            + "home=" + home.toAbsolutePath().normalize() + System.lineSeparator());
        } catch (IOException e) {
            LOGGER.warn("SDK 落位票根写入失败（不影响安装，卸载时按记录路径处理）: {}", home, e);
        }
    }

    /** 取消检查点：监听方置取消信号时立即终止（委派给下载器/阶段边界的协作语义）。 */
    private static void checkCancelled(SdkInstallListener listener) {
        if (listener.isCancelled()) {
            throw new CancellationException("安装已取消");
        }
    }

    /** 回滚本项目安装：仅删除 projectId 匹配的 PROJECT 记录与物理目录。 */
    public void rollback(LanguageEnum language, String version, String projectId) {
        repository.findByLanguageAndVersionAndStatus(language, version, InstallStatusEnum.SUCCESS)
                .stream()
                .filter(r -> projectId != null && projectId.equals(r.getProjectId()))
                .findFirst()
                .ifPresent(record -> {
                    repository.delete(record);
                    deleteRecursively(Path.of(record.getInstallPath()));
                });
    }

    private Path downloadArchive(SdkVersion version, Path dataRoot, SdkInstallListener listener) {
        Path target = dataRoot.resolve(".downloads")
                .resolve(version.getLanguage().name().toLowerCase(Locale.ROOT)
                        + "-" + version.getVersion() + ".zip");
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("下载目录创建失败: " + target.getParent(), e);
        }
        // 部分官方源（dl.google.com 等）以 chunked 传输、无 Content-Length，
        // 下载器上报 total=0；此时用目录元数据 sizeBytes 估算总数，
        // 保证用户侧百分比持续推进（进度可感知），真实长度未知时维持原语义。
        Long fallbackSize = version.getSizeBytes();
        // 适配器同时转发取消信号：下载循环每块写入前查询 isCancelled，置位即抛取消异常
        downloader.download(version.getDownloadUrl(), version.getSha256(), target, dataRoot,
                new ProgressListener() {
                    @Override
                    public void onProgress(long totalBytes, long bytesDownloaded) {
                        listener.onProgress(totalBytes > 0 || fallbackSize == null
                                ? totalBytes : fallbackSize, bytesDownloaded);
                    }

                    @Override
                    public boolean isCancelled() {
                        return listener.isCancelled();
                    }
                });
        return target;
    }

    /** .part 暂存文件路径（与下载器 PART_SUFFIX 契约对齐）。 */
    private static Path partOf(Path target) {
        return target == null ? null
                : target.resolveSibling(target.getFileName() + ResumeableDownloader.PART_SUFFIX);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时归档清理失败不影响主流程
        }
    }

    /** 递归删除（尽力而为）：取消清理场景失败仅告警，不遮蔽取消结果。 */
    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("取消清理 SDK 目录失败（残留待人工处理）: {}", root, e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.ROLLBACK_FAILED,
                    "回滚删除 SDK 目录失败: " + root, e);
        }
    }
}
