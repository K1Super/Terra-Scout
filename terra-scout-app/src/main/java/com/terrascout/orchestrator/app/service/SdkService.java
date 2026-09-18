package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.catalog.AdoptiumJavaFetcher;
import com.terrascout.orchestrator.app.service.catalog.CatalogFetchResult;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.app.service.catalog.SdkOfficialCatalogFetcher;
import com.terrascout.orchestrator.app.service.catalog.VersionComparator;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.InstallStatusEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;
import com.terrascout.orchestrator.core.enums.ScopeEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SDK 服务（rest-schema.md 3.4.10-3.4.12、3.4.18 / D-004、D-009、D-017）。
 *
 * <p>list 合并 {@code sdk_version} 元数据与 {@code sdk_install_record} 安装记录，
 * 给出 installed / recordId / installedPath，并按版本号数字段升序返回；
 * install 异步化：立即返回 jobId，后台下载 + 校验 + 解压，进度经
 * {@link SdkInstallProgressStore} 轮询；reload 官方源在线拉取 + 本地文件兜底后
 * upsert 进元数据表，并淘汰过老 JAVA 版本（major &lt; 11）。
 */
@Service
public class SdkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SdkService.class);

    private final SdkVersionRepository versionRepository;
    private final SdkInstallRecordRepository installRepository;
    private final SdkInstaller sdkInstaller;
    private final SystemSdkProber sdkProber;
    private final SdkMetadataStore metadataStore;
    private final SdkOfficialCatalogFetcher catalogFetcher;
    private final Executor sdkExecutor;
    private final SdkInstallProgressStore progressStore;

    public SdkService(SdkVersionRepository versionRepository,
                      SdkInstallRecordRepository installRepository,
                      SdkInstaller sdkInstaller,
                      SystemSdkProber sdkProber,
                      SdkMetadataStore metadataStore,
                      SdkOfficialCatalogFetcher catalogFetcher,
                      @Qualifier("taskExecutor") Executor sdkExecutor,
                      SdkInstallProgressStore progressStore) {
        this.versionRepository = versionRepository;
        this.installRepository = installRepository;
        this.sdkInstaller = sdkInstaller;
        this.sdkProber = sdkProber;
        this.metadataStore = metadataStore;
        this.catalogFetcher = catalogFetcher;
        this.sdkExecutor = sdkExecutor;
        this.progressStore = progressStore;
    }

    /** SDK 版本列表（可按维度过滤；空查询环境返回空列表）；版本号数字段升序排布。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(LanguageEnum language, OsTypeEnum os, ArchEnum arch) {
        // 内存过滤而非派生查询：JPA 对 null 参数生成 IS NULL 语义，无法表达「未指定不过滤」
        List<SdkVersion> versions = versionRepository.findAll().stream()
                .filter(v -> language == null || v.getLanguage() == language)
                .filter(v -> os == null || v.getOs() == os)
                .filter(v -> arch == null || v.getArch() == arch)
                .filter(SdkService::notStaleJava)
                .collect(Collectors.toList());
        Map<String, SdkInstallRecord> installedMap = installedByKey();
        // HashMap 支持 language=null 的空查询（ImmutableCollections 对 null key 抛 NPE）
        Map<LanguageEnum, Map<String, String>> systemMap =
                new HashMap<>(sdkProber.probeVersions());
        Set<String> catalogKeys = new HashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (SdkVersion version : versions) {
            String key = key(version.getLanguage(), version.getVersion());
            catalogKeys.add(key);
            SdkInstallRecord record = installedMap.get(key);
            String systemHome = systemHome(systemMap, version.getLanguage(), version.getVersion());
            boolean byRecord = record != null && isSuccess(record);
            boolean bySystem = systemHome != null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("language", version.getLanguage().name());
            item.put("version", version.getVersion());
            item.put("os", version.getOs().name());
            item.put("arch", version.getArch().name());
            item.put("downloadUrl", version.getDownloadUrl());
            item.put("sha256", version.getSha256());
            item.put("sizeBytes", version.getSizeBytes());
            item.put("lts", version.isLts());
            item.put("eol", version.isEol());
            item.put("releaseTime", version.getReleaseTime());
            // 安装落地路径对全部目录条目可见（已装=记录实际路径，未装=标准目的路径），消除空间盲区
            item.put("installPath", byRecord ? record.getInstallPath()
                    : PathConstants.sdkHome(PathConstants.dataRoot(),
                            version.getLanguage(), version.getVersion()).toString());
            item.put("installed", byRecord || bySystem);
            item.put("systemInstalled", bySystem && !byRecord);
            if (byRecord) {
                item.put("recordId", record.getId());
                item.put("installedPath", record.getInstallPath());
            } else if (bySystem) {
                item.put("installedPath", systemHome);
            }
            items.add(item);
        }
        // 系统项合成（R29）：元数据未收录的系统已装版本也要可见（全新安装元数据为空时页面不空）；
        // 与 catalog 同样遵守语言过滤，探测面仅本机 WINDOWS/AMD64
        for (Map.Entry<LanguageEnum, Map<String, String>> languageEntry : systemMap.entrySet()) {
            LanguageEnum lang = languageEntry.getKey();
            if (language != null && lang != language) {
                continue;
            }
            if (os != null && OsTypeEnum.WINDOWS != os
                    || arch != null && ArchEnum.AMD64 != arch) {
                continue;
            }
            for (Map.Entry<String, String> versionEntry : languageEntry.getValue().entrySet()) {
                if (catalogKeys.contains(key(lang, versionEntry.getKey()))) {
                    continue;
                }
                if (lang == LanguageEnum.JAVA && isStaleJava(versionEntry.getKey())) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("language", lang.name());
                item.put("version", versionEntry.getKey());
                item.put("os", OsTypeEnum.WINDOWS.name());
                item.put("arch", ArchEnum.AMD64.name());
                item.put("installed", true);
                item.put("systemInstalled", true);
                item.put("installedPath", versionEntry.getValue());
                items.add(item);
            }
        }
        // 统一排布：版本号数字段升序（低版本 → 高版本），catalog 与系统合成项同规则
        items.sort((left, right) -> VersionComparator.compare(
                (String) left.get("version"), (String) right.get("version")));
        return items;
    }

    /**
     * 安装：立即返回异步任务 jobId（3.4.18 轮询进度）；后台真实下载 + 校验 + 解压 +
     * 幂等写记录。已装版本直接返回 SUCCESS 结果（无需任务）。
     *
     * <p>installDir 可选（3.4.11，R45）：用户自选目录作为仓库根，自动落位
     * {@code 所选目录\{语言小写}\{版本}}；缺省回落 D-004 标准仓库。已装复用分支
     * 不校验 installDir（复用语义不变）。
     */
    public Map<String, Object> install(LanguageEnum language, String version,
                                       ScopeEnum scope, String projectId, String installDir) {
        SdkVersion sdkVersion = versionRepository
                .findByLanguageAndOsAndArch(language, OsTypeEnum.WINDOWS, ArchEnum.AMD64).stream()
                .filter(v -> v.getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new TerraScoutException(TerraScoutError.NO_SDK_VERSION_MATCH,
                        "SDK 版本元数据不存在: " + language + " " + version));
        ScopeEnum effectiveScope = scope == null ? ScopeEnum.GLOBAL : scope;
        Optional<SdkInstallRecord> existing = sdkInstaller.findInstalled(language, version);
        if (existing.isPresent()) {
            SdkInstallRecord record = existing.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recordId", record.getId());
            result.put("language", record.getLanguage().name());
            result.put("version", record.getVersion());
            result.put("installedPath", record.getInstallPath());
            result.put("status", record.getStatus().name());
            return result;
        }
        Path home = resolveInstallHome(language, version, installDir);
        String jobId = UUID.randomUUID().toString();
        SdkInstallProgressStore.Snapshot progress =
                progressStore.open(jobId, language, version, home.toString());
        try {
            sdkExecutor.execute(() -> runInstall(jobId, progress, sdkVersion,
                    effectiveScope, projectId, home));
        } catch (RuntimeException e) {
            progress.fail("任务队列已满，请稍后重试");
            throw new TerraScoutException(TerraScoutError.TASK_QUEUE_FULL,
                    "SDK 安装任务队列已满，请稍后重试");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("language", language.name());
        result.put("version", version);
        result.put("status", "QUEUED");
        // 落地路径随任务下发：安装在哪里、占用哪里空间，全程透明
        result.put("installPath", progress.toMap().get("installPath"));
        return result;
    }

    /** 解析安装落位目录（R45）：installDir 非空 = 自定义根，校验后拼 {根}\{语言}\{版本}；空 = 标准仓库。 */
    private static Path resolveInstallHome(LanguageEnum language, String version, String installDir) {
        if (installDir == null || installDir.isBlank()) {
            return PathConstants.sdkHome(PathConstants.dataRoot(), language, version);
        }
        Path base = Path.of(installDir);
        if (!base.isAbsolute()) {
            throw new TerraScoutException(TerraScoutError.INVALID_PROJECT_PATH,
                    "自定义安装目录必须为绝对路径: " + installDir);
        }
        base = base.toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.INVALID_PROJECT_PATH,
                    "自定义安装目录无法创建或不可写: " + base, e);
        }
        Path home = base.resolve(language.name().toLowerCase(Locale.ROOT)).resolve(version);
        if (Files.exists(home)) {
            throw new TerraScoutException(TerraScoutError.ISOLATION_DIR_CONFLICT,
                    "目标 SDK 目录已存在，为避免混乱拒绝覆盖: " + home);
        }
        return home;
    }

    /** 安装进度查询（3.4.18）：按 jobId 取内存快照。 */
    public Map<String, Object> installProgress(String jobId) {
        return progressStore.get(jobId)
                .map(SdkInstallProgressStore.Snapshot::toMap)
                .orElseThrow(() -> new TerraScoutException(TerraScoutError.TASK_NOT_FOUND,
                        "SDK 安装任务不存在: " + jobId));
    }

    /**
     * 取消安装任务（3.4.18）：置取消信号并返回最新快照。工作线程在最近检查点
     * （下载块 / 阶段边界）中止，清理 .part 与半解压残留后收敛 CANCELLED 终态。
     * 任务已终态（DONE / FAILED / CANCELLED）拒绝（409005）。
     */
    public Map<String, Object> cancelInstall(String jobId) {
        SdkInstallProgressStore.Snapshot snapshot = progressStore.get(jobId)
                .orElseThrow(() -> new TerraScoutException(TerraScoutError.TASK_NOT_FOUND,
                        "SDK 安装任务不存在: " + jobId));
        if (snapshot.isFinished()) {
            throw new TerraScoutException(TerraScoutError.TASK_TERMINAL_STATE,
                    "安装任务已结束，无法取消: " + jobId);
        }
        snapshot.requestCancel();
        return snapshot.toMap();
    }

    /**
     * 安装工作线程体：全程经 listener 上报阶段与下载字节进度；取消在安装器内完成
     * 残留清理后以取消异常抛出，此处收敛为 CANCELLED 终态；其余异常收敛为 FAILED。
     */
    private void runInstall(String jobId, SdkInstallProgressStore.Snapshot progress,
                            SdkVersion sdkVersion, ScopeEnum scope, String projectId, Path home) {
        if (progress.isCancelled()) {
            // 排队期间被取消：从未产生任何文件，直接收敛
            progress.cancelledDone("安装已取消");
            return;
        }
        try {
            SdkInstallRecord record = sdkInstaller.ensureInstalled(sdkVersion, scope, projectId, home,
                    new SdkInstallListener() {
                        @Override
                        public void onStage(String stage, String message) {
                            progress.stage(stage, message);
                        }

                        @Override
                        public void onProgress(long totalBytes, long bytesDownloaded) {
                            progress.downloadProgress(totalBytes, bytesDownloaded);
                        }

                        @Override
                        public boolean isCancelled() {
                            return progress.isCancelled();
                        }
                    });
            progress.succeed(record.getId());
        } catch (CancellationException e) {
            LOGGER.info("SDK 安装已取消 jobId={} language={} version={}（残留已清理）",
                    jobId, sdkVersion.getLanguage(), sdkVersion.getVersion());
            progress.cancelledDone("安装已取消，下载暂存与解压残留已清理");
        } catch (RuntimeException e) {
            LOGGER.error("SDK 安装失败 jobId={} language={} version={}",
                    jobId, sdkVersion.getLanguage(), sdkVersion.getVersion(), e);
            progress.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 卸载：物理目录先行摘除（引用计数保护全局共享 + 原子重命名进 .trash-* 后尽力删除），
     * 再删记录；目录被占用时抛 500004 且记录保留，列表与磁盘状态始终一致。
     */
    @Transactional
    public Map<String, Object> uninstall(String recordId) {
        SdkInstallRecord record = installRepository.findById(recordId)
                .orElseThrow(() -> new TerraScoutException(TerraScoutError.PLAN_NOT_FOUND,
                        "SDK 安装记录不存在: " + recordId));
        boolean shared = installRepository
                .findByLanguageAndVersionAndStatus(record.getLanguage(), record.getVersion(),
                        InstallStatusEnum.SUCCESS).stream()
                .anyMatch(r -> !r.getId().equals(recordId));
        boolean directoryRemoved = false;
        if (!shared) {
            directoryRemoved = removeInstallDirectory(record);
        }
        installRepository.delete(record);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", recordId);
        result.put("status", "UNINSTALLED");
        result.put("directoryRemoved", directoryRemoved);
        result.put("installPath", record.getInstallPath());
        return result;
    }

    /**
     * 删除安装目录：接受标准 SDK 路径或携带落位票根的自定义路径（护栏防历史脏数据外删），
     * 原子重命名为 {@code .trash-<uuid>} 后尽力递归删除（重命名即摘除占用，失败清理残留由卸载侧清扫）。
     */
    private boolean removeInstallDirectory(SdkInstallRecord record) {
        Path standard = PathConstants.sdkHome(PathConstants.dataRoot(),
                record.getLanguage(), record.getVersion()).toAbsolutePath().normalize();
        Path recorded = Path.of(record.getInstallPath()).toAbsolutePath().normalize();
        boolean customMarked = Files.exists(recorded.resolve(PathConstants.FILE_SDK_MARKER));
        if (!recorded.equals(standard) && !customMarked) {
            LOGGER.warn("卸载记录路径非标准且无落位票根，仅删记录不删目录: {}", recorded);
            return false;
        }
        sweepStaleTrash(recorded);
        if (!Files.exists(recorded)) {
            return false;
        }
        Path trash = recorded.resolveSibling(recorded.getFileName() + ".trash-" + UUID.randomUUID());
        try {
            Files.move(recorded, trash);
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.ROLLBACK_FAILED,
                    "SDK 目录被占用，卸载中止（记录未删除）: " + recorded, e);
        }
        deleteRecursivelyQuietly(trash);
        return true;
    }

    /** 清扫历史遗留 .trash-* 目录（上次卸载残留清理失败场景）。 */
    private void sweepStaleTrash(Path home) {
        Path parent = home.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        String prefix = home.getFileName().toString() + ".trash-";
        try (Stream<Path> entries = Files.list(parent)) {
            entries.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(this::deleteRecursivelyQuietly);
        } catch (IOException e) {
            LOGGER.warn("清扫卸载残留目录失败: {}", parent, e);
        }
    }

    /** 递归删除（尽力而为）失败仅告警，不阻断卸载结果（残留可用时由下次卸载清扫）。 */
    private void deleteRecursivelyQuietly(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("删除 SDK 目录残留失败（可下次卸载时重试清理）: {}", root, e);
        }
    }

    /**
     * 重载 SDK 元数据（rest-schema 3.4.12 / D-009）：官方源在线拉取 → 分语言 upsert
     * → 淘汰过老 JAVA（major &lt; {@link AdoptiumJavaFetcher#MIN_MAJOR}）
     * → 响应分语言状态。全部语言失败时回退本地外部文件；两者皆不可用抛 502001。
     */
    @Transactional
    public Map<String, Object> reloadMetadata() {
        CatalogFetchResult fetched = catalogFetcher.fetchAll();
        List<Map<String, Object>> sources = new ArrayList<>();
        int total = 0;
        for (LanguageEnum language : LanguageEnum.values()) {
            String error = fetched.getErrors().get(language);
            List<SdkCatalogEntry> entries = fetched.getEntries().getOrDefault(language, List.of());
            int count = error == null ? metadataStore.upsertAll(entries) : 0;
            total += count;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("language", language.name());
            source.put("status", error == null ? "SUCCESS" : "FAILED");
            source.put("count", count);
            if (error != null) {
                source.put("message", error);
            }
            sources.add(source);
        }
        int purgedStaleJava = purgeStaleJava();
        boolean fallback = false;
        if (total == 0) {
            Path metadata = PathConstants.sdkMetadata(PathConstants.dataRoot());
            try {
                total = metadataStore.upsertAll(metadataStore.loadFromFile(metadata));
            } catch (IOException e) {
                LOGGER.warn("SDK 元数据本地文件读取失败: {}", e.getMessage());
            }
            if (total == 0) {
                throw new TerraScoutException(TerraScoutError.SDK_SOURCE_UNREACHABLE,
                        "官方源与所有镜像拉取失败，且本地元数据文件不可用");
            }
            fallback = true;
        }
        persistExternalFile();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loaded", true);
        result.put("sdkCount", total);
        result.put("updatedAt", Instant.now().toString());
        result.put("sources", sources);
        result.put("purgedStaleJava", purgedStaleJava);
        if (fallback) {
            result.put("fallback", "LOCAL_FILE");
        }
        return result;
    }

    /** 淘汰过老 JAVA 版本元数据（major &lt; MIN_MAJOR），返回删除条数（rest-schema 3.4.12）。 */
    private int purgeStaleJava() {
        List<SdkVersion> stale = versionRepository.findAll().stream()
                .filter(v -> v.getLanguage() == LanguageEnum.JAVA)
                .filter(v -> VersionComparator.major(v.getVersion()) < AdoptiumJavaFetcher.MIN_MAJOR)
                .collect(Collectors.toList());
        if (!stale.isEmpty()) {
            versionRepository.deleteAll(stale);
            LOGGER.info("淘汰过老 JAVA 版本元数据 {} 条: {}",
                    stale.size(), stale.stream().map(SdkVersion::getVersion)
                            .collect(Collectors.toList()));
        }
        return stale.size();
    }

    /** 过老 JAVA（major &lt; MIN_MAJOR）不可见；其余语言与系统合成版本不受限。 */
    private static boolean notStaleJava(SdkVersion version) {
        return version.getLanguage() != LanguageEnum.JAVA || !isStaleJava(version.getVersion());
    }

    /** 版本号是否为过老 JAVA（major &lt; MIN_MAJOR，如 1.8.0_504）。 */
    private static boolean isStaleJava(String version) {
        return VersionComparator.major(version) < AdoptiumJavaFetcher.MIN_MAJOR;
    }

    /** 库内元数据写回外部文件；失败仅告警，不阻断刷新结果（下次刷新重写）。 */
    private void persistExternalFile() {
        try {
            metadataStore.writeExternalFile(PathConstants.sdkMetadata(PathConstants.dataRoot()));
        } catch (IOException e) {
            LOGGER.warn("SDK 元数据写回外部文件失败（仅内存生效）: {}", e.getMessage());
        }
    }

    private Map<String, SdkInstallRecord> installedByKey() {
        return installRepository.findAll().stream()
                .filter(rec -> isSuccess(rec))
                .collect(Collectors.toMap(
                        rec -> key(rec.getLanguage(), rec.getVersion()),
                        rec -> rec,
                        (a, b) -> a));
    }

    private static boolean isSuccess(SdkInstallRecord record) {
        return record.getStatus() == InstallStatusEnum.SUCCESS;
    }

    private static String systemHome(Map<LanguageEnum, Map<String, String>> systemMap,
                                     LanguageEnum language, String version) {
        Map<String, String> byVersion = systemMap.get(language);
        return byVersion == null ? null : byVersion.get(version);
    }

    private static String key(LanguageEnum language, String version) {
        return language.name() + ":" + version;
    }
}
