package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.SdkInstallListener;
import com.terrascout.orchestrator.app.service.SdkInstallProgressStore;
import com.terrascout.orchestrator.app.service.SdkInstaller;
import com.terrascout.orchestrator.app.service.SdkMetadataStore;
import com.terrascout.orchestrator.app.service.SdkService;
import com.terrascout.orchestrator.app.service.SystemSdkProber;
import com.terrascout.orchestrator.app.service.catalog.CatalogFetchResult;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.app.service.catalog.SdkOfficialCatalogFetcher;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDK 服务单测（3.4.10-3.4.12、3.4.18 / D-004、D-009、D-017）：列表合并安装状态与升序排布 /
 * 异步安装与进度快照 / 卸载 / 元数据重载与过老 JAVA 淘汰。
 */
class SdkServiceTest {

    private final SdkVersionRepository versionRepo = mock(SdkVersionRepository.class);
    private final SdkInstallRecordRepository installRepo = mock(SdkInstallRecordRepository.class);
    private final SdkInstaller sdkInstaller = mock(SdkInstaller.class);
    private final SystemSdkProber sdkProber = mock(SystemSdkProber.class);
    private final SdkMetadataStore metadataStore = mock(SdkMetadataStore.class);
    private final SdkOfficialCatalogFetcher catalogFetcher = mock(SdkOfficialCatalogFetcher.class);
    private final SdkInstallProgressStore progressStore = new SdkInstallProgressStore();
    /** 同步直跑执行器：runInstall 内联完成，便于断言快照终态。 */
    private final Executor executor = Runnable::run;
    private final SdkService service = new SdkService(
            versionRepo, installRepo, sdkInstaller, sdkProber, metadataStore, catalogFetcher,
            executor, progressStore);

    @TempDir
    Path dataRoot;

    @BeforeEach
    void setUp() {
        System.setProperty(PathConstants.DATA_DIR_PROPERTY, dataRoot.toString());
        when(sdkProber.probeVersions()).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(PathConstants.DATA_DIR_PROPERTY);
    }

    private SdkVersion version(String v) {
        SdkVersion version = new SdkVersion();
        version.setLanguage(LanguageEnum.JAVA);
        version.setVersion(v);
        version.setOs(OsTypeEnum.WINDOWS);
        version.setArch(ArchEnum.AMD64);
        version.setDownloadUrl("https://example.com/" + v);
        version.setSha256("a".repeat(64));
        version.setLts(true);
        return version;
    }

    @Test
    void listMarksInstalledFromSuccessRecord() {
        when(versionRepo.findAll()).thenReturn(List.of(version("17.0.9")));
        SdkInstallRecord rec = new SdkInstallRecord();
        rec.setId("r1");
        rec.setLanguage(LanguageEnum.JAVA);
        rec.setVersion("17.0.9");
        rec.setInstallPath("C:\\fake\\sdk\\17.0.9");
        rec.setStatus(InstallStatusEnum.SUCCESS);
        when(installRepo.findAll()).thenReturn(List.of(rec));

        List<Map<String, Object>> items = service.list(
                LanguageEnum.JAVA, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
        assertThat(items).hasSize(1);
        assertThat((Boolean) items.get(0).get("installed")).isTrue();
        assertThat((String) items.get(0).get("recordId")).isEqualTo("r1");
        assertThat((String) items.get(0).get("installedPath")).isEqualTo("C:\\fake\\sdk\\17.0.9");
        // R45：已装条目 installPath 展示记录实际路径（含自定义落位），消除空间盲区
        assertThat((String) items.get(0).get("installPath")).isEqualTo("C:\\fake\\sdk\\17.0.9");
    }

    @Test
    void installReturnsExistingRecordSynchronously() {
        when(versionRepo.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(version("17.0.9")));
        SdkInstallRecord rec = new SdkInstallRecord();
        rec.setId("r0");
        rec.setLanguage(LanguageEnum.JAVA);
        rec.setVersion("17.0.9");
        rec.setInstallPath("C:\\fake\\sdk\\17.0.9");
        rec.setStatus(InstallStatusEnum.SUCCESS);
        when(sdkInstaller.findInstalled(eq(LanguageEnum.JAVA), eq("17.0.9")))
                .thenReturn(Optional.of(rec));

        Map<String, Object> result = service.install(LanguageEnum.JAVA, "17.0.9", ScopeEnum.GLOBAL, null, null);
        assertThat((String) result.get("recordId")).isEqualTo("r0");
        assertThat((String) result.get("status")).isEqualTo("SUCCESS");
        assertThat(result.containsKey("jobId")).isFalse();
        verify(sdkInstaller, never()).ensureInstalled(any(), any(), any(), any(), any());
    }

    @Test
    void installQueuesAsyncJobAndPublishesProgress() {
        when(versionRepo.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(version("17.0.9")));
        when(sdkInstaller.findInstalled(eq(LanguageEnum.JAVA), eq("17.0.9")))
                .thenReturn(Optional.empty());
        SdkInstallRecord rec = new SdkInstallRecord();
        rec.setId("r0");
        rec.setLanguage(LanguageEnum.JAVA);
        rec.setVersion("17.0.9");
        rec.setInstallPath("C:\\fake\\sdk\\17.0.9");
        rec.setStatus(InstallStatusEnum.SUCCESS);
        when(sdkInstaller.ensureInstalled(any(SdkVersion.class), eq(ScopeEnum.GLOBAL), isNull(),
                eq(PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "17.0.9")), any(SdkInstallListener.class))).thenReturn(rec);

        Map<String, Object> result = service.install(LanguageEnum.JAVA, "17.0.9", ScopeEnum.GLOBAL, null, null);
        assertThat((String) result.get("status")).isEqualTo("QUEUED");
        String jobId = (String) result.get("jobId");
        assertThat(jobId).isNotBlank();

        Map<String, Object> progress = service.installProgress(jobId);
        assertThat((Boolean) progress.get("finished")).isTrue();
        assertThat((Boolean) progress.get("success")).isTrue();
        assertThat((String) progress.get("recordId")).isEqualTo("r0");
        // 快照落地路径为 D-004 标准目录（真实落位路径透明可见）
        assertThat((String) progress.get("installPath"))
                .isEqualTo(PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "17.0.9").toString());
        // QUEUED 响应即携带落地路径
        assertThat((String) result.get("installPath"))
                .isEqualTo(PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "17.0.9").toString());
    }

    @Test
    void installWithCustomDirReportsSelectedHome() {
        when(versionRepo.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(version("17.0.9")));
        when(sdkInstaller.findInstalled(eq(LanguageEnum.JAVA), eq("17.0.9")))
                .thenReturn(Optional.empty());
        SdkInstallRecord rec = new SdkInstallRecord();
        rec.setId("r0");
        rec.setStatus(InstallStatusEnum.SUCCESS);
        when(sdkInstaller.ensureInstalled(any(SdkVersion.class), eq(ScopeEnum.GLOBAL), isNull(),
                any(Path.class), any(SdkInstallListener.class))).thenReturn(rec);

        Path base = dataRoot.resolve("user-picked");
        Map<String, Object> result = service.install(LanguageEnum.JAVA, "17.0.9",
                ScopeEnum.GLOBAL, null, base.toString());
        Path home = base.resolve("java").resolve("17.0.9");

        assertThat((String) result.get("status")).isEqualTo("QUEUED");
        // 自动建夹结构：{所选根}\{语言小写}\{版本}，且所选根已即时创建
        assertThat((String) result.get("installPath")).isEqualTo(home.toString());
        assertThat(Files.exists(base)).isTrue();
        Map<String, Object> progress = service.installProgress((String) result.get("jobId"));
        assertThat((String) progress.get("installPath")).isEqualTo(home.toString());
        // 工作线程以自定义落位目录执行安装
        verify(sdkInstaller).ensureInstalled(any(SdkVersion.class), eq(ScopeEnum.GLOBAL), isNull(),
                eq(home), any(SdkInstallListener.class));
    }

    @Test
    void installRejectsRelativeCustomDir() {
        when(versionRepo.findByLanguageAndOsAndArch(any(), any(), any()))
                .thenReturn(List.of(version("17.0.9")));
        when(sdkInstaller.findInstalled(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.install(LanguageEnum.JAVA, "17.0.9",
                ScopeEnum.GLOBAL, null, "relative\\dir"))
                .isInstanceOfSatisfying(TerraScoutException.class,
                        e -> assertThat(e.getError()).isEqualTo(TerraScoutError.INVALID_PROJECT_PATH));
    }

    @Test
    void installRejectsOccupiedCustomHome() throws IOException {
        when(versionRepo.findByLanguageAndOsAndArch(any(), any(), any()))
                .thenReturn(List.of(version("17.0.9")));
        when(sdkInstaller.findInstalled(any(), any())).thenReturn(Optional.empty());
        Path home = dataRoot.resolve("picked").resolve("java").resolve("17.0.9");
        Files.createDirectories(home);
        Files.writeString(home.resolve("occupied.txt"), "x");

        assertThatThrownBy(() -> service.install(LanguageEnum.JAVA, "17.0.9",
                ScopeEnum.GLOBAL, null, dataRoot.resolve("picked").toString()))
                .isInstanceOfSatisfying(TerraScoutException.class,
                        e -> assertThat(e.getError())
                                .isEqualTo(TerraScoutError.ISOLATION_DIR_CONFLICT));
    }

    @Test
    void uninstallRemovesMarkedCustomDirectory() throws IOException {
        // 自定义目录安装的落位票根护栏：非标准路径但携带票根即物理删除
        Path custom = dataRoot.resolve("picked").resolve("java").resolve("17.0.9");
        Files.createDirectories(custom);
        Files.writeString(custom.resolve(PathConstants.FILE_SDK_MARKER),
                "language=JAVA\nversion=17.0.9\n");
        SdkInstallRecord rec = new SdkInstallRecord();
        rec.setId("r1");
        rec.setLanguage(LanguageEnum.JAVA);
        rec.setVersion("17.0.9");
        rec.setInstallPath(custom.toString());
        rec.setStatus(InstallStatusEnum.SUCCESS);
        when(installRepo.findById("r1")).thenReturn(Optional.of(rec));
        when(installRepo.findByLanguageAndVersionAndStatus(
                eq(LanguageEnum.JAVA), eq("17.0.9"), eq(InstallStatusEnum.SUCCESS)))
                .thenReturn(List.of(rec));

        Map<String, Object> result = service.uninstall("r1");

        assertThat((Boolean) result.get("directoryRemoved")).isTrue();
        assertThat(Files.exists(custom)).isFalse();
        verify(installRepo).delete(rec);
    }

    @Test
    void installProgressUnknownJobThrows404002() {
        assertThatThrownBy(() -> service.installProgress("missing"))
                .isInstanceOfSatisfying(TerraScoutException.class,
                        e -> assertThat(e.getError()).isEqualTo(TerraScoutError.TASK_NOT_FOUND));
    }

    @Test
    void cancelInstallConvergesCancelledWhenWorkerAborts() {
        when(versionRepo.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(version("17.0.9")));
        when(sdkInstaller.findInstalled(eq(LanguageEnum.JAVA), eq("17.0.9")))
                .thenReturn(Optional.empty());
        // 工作线程下载中途用户取消 → 安装器清理残留后抛取消异常（同步执行器内联收敛）
        when(sdkInstaller.ensureInstalled(any(SdkVersion.class), eq(ScopeEnum.GLOBAL), isNull(),
                eq(PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "17.0.9")), any(SdkInstallListener.class)))
                .thenThrow(new CancellationException("安装已取消"));

        Map<String, Object> started = service.install(LanguageEnum.JAVA, "17.0.9",
                ScopeEnum.GLOBAL, null, null);
        String jobId = (String) started.get("jobId");

        Map<String, Object> progress = service.installProgress(jobId);
        assertThat((String) progress.get("stage")).isEqualTo("CANCELLED");
        assertThat((Boolean) progress.get("finished")).isTrue();
        assertThat((Boolean) progress.get("success")).isFalse();
        assertThat((Boolean) progress.get("cancelled")).isTrue();
        assertThat((String) progress.get("message")).contains("已清理");
    }

    @Test
    void cancelInstallRejectsFinishedJobWith409005() {
        when(versionRepo.findByLanguageAndOsAndArch(
                eq(LanguageEnum.JAVA), eq(OsTypeEnum.WINDOWS), eq(ArchEnum.AMD64)))
                .thenReturn(List.of(version("17.0.9")));
        when(sdkInstaller.findInstalled(eq(LanguageEnum.JAVA), eq("17.0.9")))
                .thenReturn(Optional.empty());
        when(sdkInstaller.ensureInstalled(any(SdkVersion.class), eq(ScopeEnum.GLOBAL), isNull(),
                eq(PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "17.0.9")), any(SdkInstallListener.class)))
                .thenAnswer(inv -> {
                    SdkInstallRecord rec = new SdkInstallRecord();
                    rec.setId("r0");
                    rec.setStatus(InstallStatusEnum.SUCCESS);
                    return rec;
                });
        String jobId = (String) service.install(LanguageEnum.JAVA, "17.0.9",
                ScopeEnum.GLOBAL, null, null).get("jobId");

        assertThatThrownBy(() -> service.cancelInstall(jobId))
                .isInstanceOfSatisfying(TerraScoutException.class,
                        e -> assertThat(e.getError()).isEqualTo(TerraScoutError.TASK_TERMINAL_STATE));
    }

    @Test
    void cancelInstallSignalsCancellingBeforeWorkerConverges() {
        // 运行中任务：注册快照后置取消信号，应呈现 CANCELLING、cancelled=true 且未终态
        progressStore.open("j-running", LanguageEnum.JAVA, "17.0.9");

        Map<String, Object> snapshot = service.cancelInstall("j-running");

        assertThat((String) snapshot.get("stage")).isEqualTo("CANCELLING");
        assertThat((Boolean) snapshot.get("cancelled")).isTrue();
        assertThat((Boolean) snapshot.get("finished")).isFalse();
    }

    @Test
    void cancelInstallUnknownJobThrows404002() {
        assertThatThrownBy(() -> service.cancelInstall("missing"))
                .isInstanceOfSatisfying(TerraScoutException.class,
                        e -> assertThat(e.getError()).isEqualTo(TerraScoutError.TASK_NOT_FOUND));
    }

    @Test
    void uninstallRemovesDirectoryWhenLastReference() throws IOException {
        Path home = PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "17.0.9");
        Files.createDirectories(home);
        Files.writeString(home.resolve("release"), "x");
        SdkInstallRecord rec = new SdkInstallRecord();
        rec.setId("r1");
        rec.setLanguage(LanguageEnum.JAVA);
        rec.setVersion("17.0.9");
        rec.setInstallPath(home.toString());
        rec.setStatus(InstallStatusEnum.SUCCESS);
        when(installRepo.findById("r1")).thenReturn(Optional.of(rec));
        when(installRepo.findByLanguageAndVersionAndStatus(
                eq(LanguageEnum.JAVA), eq("17.0.9"), eq(InstallStatusEnum.SUCCESS)))
                .thenReturn(List.of(rec));

        Map<String, Object> result = service.uninstall("r1");

        assertThat((Boolean) result.get("directoryRemoved")).isTrue();
        assertThat(Files.exists(home)).isFalse();
        verify(installRepo).delete(rec);
    }

    @Test
    void uninstallKeepsSharedDirectoryWithoutLastReference() throws IOException {
        Path home = PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "17.0.9");
        Files.createDirectories(home);
        SdkInstallRecord rec1 = new SdkInstallRecord();
        rec1.setId("r1");
        rec1.setLanguage(LanguageEnum.JAVA);
        rec1.setVersion("17.0.9");
        rec1.setInstallPath(home.toString());
        rec1.setStatus(InstallStatusEnum.SUCCESS);
        SdkInstallRecord rec2 = new SdkInstallRecord();
        rec2.setId("r2");
        rec2.setLanguage(LanguageEnum.JAVA);
        rec2.setVersion("17.0.9");
        rec2.setInstallPath(home.toString());
        rec2.setStatus(InstallStatusEnum.SUCCESS);
        when(installRepo.findById("r1")).thenReturn(Optional.of(rec1));
        when(installRepo.findByLanguageAndVersionAndStatus(
                eq(LanguageEnum.JAVA), eq("17.0.9"), eq(InstallStatusEnum.SUCCESS)))
                .thenReturn(List.of(rec1, rec2));

        Map<String, Object> result = service.uninstall("r1");

        assertThat((Boolean) result.get("directoryRemoved")).isFalse();
        assertThat(Files.exists(home)).isTrue();
        verify(installRepo).delete(rec1);
    }

    @Test
    void uninstallSkipsDirectoryOutsideStandardPath() throws IOException {
        Path weird = dataRoot.resolve("weird-dir");
        Files.createDirectories(weird);
        SdkInstallRecord rec = new SdkInstallRecord();
        rec.setId("r1");
        rec.setLanguage(LanguageEnum.JAVA);
        rec.setVersion("17.0.9");
        rec.setInstallPath(weird.toString());
        rec.setStatus(InstallStatusEnum.SUCCESS);
        when(installRepo.findById("r1")).thenReturn(Optional.of(rec));
        when(installRepo.findByLanguageAndVersionAndStatus(
                eq(LanguageEnum.JAVA), eq("17.0.9"), eq(InstallStatusEnum.SUCCESS)))
                .thenReturn(List.of(rec));

        Map<String, Object> result = service.uninstall("r1");

        // 护栏：非标准路径仅删记录，绝不外删
        assertThat((Boolean) result.get("directoryRemoved")).isFalse();
        assertThat(Files.exists(weird)).isTrue();
        verify(installRepo).delete(rec);
    }

    @Test
    void installThrowsWhenVersionMetadataMissing() {
        when(versionRepo.findByLanguageAndOsAndArch(any(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.install(LanguageEnum.JAVA, "17.0.9", null, null, null))
                .isInstanceOf(TerraScoutException.class);
    }

    @Test
    void uninstallUnknownRecordThrows() {
        when(installRepo.findById("missing")).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.uninstall("missing"))
                .isInstanceOf(TerraScoutException.class);
    }

    private SdkCatalogEntry catalogEntry(LanguageEnum language, String version) {
        SdkCatalogEntry entry = new SdkCatalogEntry();
        entry.setLanguage(language);
        entry.setVersion(version);
        entry.setOs(OsTypeEnum.WINDOWS);
        entry.setArch(ArchEnum.AMD64);
        entry.setDownloadUrl("https://nodejs.org/dist/" + version + "/sdk.zip");
        entry.setSha256("a".repeat(64));
        return entry;
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadMetadataAggregatesOfficialSourcesPerLanguage() {
        CatalogFetchResult fetched = new CatalogFetchResult(
                Map.of(LanguageEnum.JAVA, List.of(catalogEntry(LanguageEnum.JAVA, "21.0.12.1")),
                        LanguageEnum.NODE, List.of(catalogEntry(LanguageEnum.NODE, "22.20.0")),
                        LanguageEnum.PYTHON, List.of(catalogEntry(LanguageEnum.PYTHON, "3.12.10")),
                        LanguageEnum.GO, List.of()),
                Map.of(LanguageEnum.GO, "go 官方源不可达"));
        when(catalogFetcher.fetchAll()).thenReturn(fetched);
        when(metadataStore.upsertAll(anyList())).thenReturn(1);
        when(versionRepo.findAll()).thenReturn(List.of());

        Map<String, Object> result = service.reloadMetadata();

        assertThat((Boolean) result.get("loaded")).isTrue();
        assertThat((Integer) result.get("sdkCount")).isEqualTo(3);
        assertThat(result.containsKey("fallback")).isFalse();
        assertThat((String) result.get("updatedAt")).isNotBlank();
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");
        assertThat(sources).hasSize(4);
        assertThat(sources).allSatisfy(source -> {
            String status = (String) source.get("status");
            String message = (String) source.get("message");
            if ("GO".equals(source.get("language"))) {
                assertThat(status).isEqualTo("FAILED");
                assertThat(message).isEqualTo("go 官方源不可达");
                assertThat((Integer) source.get("count")).isZero();
            } else {
                assertThat(status).isEqualTo("SUCCESS");
                assertThat(message).isNull();
                assertThat((Integer) source.get("count")).isEqualTo(1);
            }
        });
        // 失败语言不触发入库
        verify(metadataStore, times(3)).upsertAll(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadMetadataFallsBackToLocalFileWhenAllSourcesFail() throws Exception {
        Map<LanguageEnum, String> errors = new EnumMap<>(LanguageEnum.class);
        for (LanguageEnum language : LanguageEnum.values()) {
            errors.put(language, language.name() + " 官方源不可达");
        }
        when(catalogFetcher.fetchAll()).thenReturn(new CatalogFetchResult(Map.of(), errors));
        when(metadataStore.loadFromFile(any(Path.class)))
                .thenReturn(List.of(catalogEntry(LanguageEnum.JAVA, "17.0.20.1")));
        when(metadataStore.upsertAll(anyList())).thenReturn(1);
        when(versionRepo.findAll()).thenReturn(List.of());

        Map<String, Object> result = service.reloadMetadata();

        assertThat((String) result.get("fallback")).isEqualTo("LOCAL_FILE");
        assertThat((Integer) result.get("sdkCount")).isEqualTo(1);
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");
        assertThat(sources).hasSize(4);
        assertThat(sources).allSatisfy(source ->
                assertThat((String) source.get("status")).isEqualTo("FAILED"));
    }

    @Test
    void reloadMetadataThrows502001WhenEverythingUnavailable() throws Exception {
        Map<LanguageEnum, String> errors = new EnumMap<>(LanguageEnum.class);
        for (LanguageEnum language : LanguageEnum.values()) {
            errors.put(language, language.name() + " 官方源不可达");
        }
        when(catalogFetcher.fetchAll()).thenReturn(new CatalogFetchResult(Map.of(), errors));
        when(metadataStore.loadFromFile(any(Path.class))).thenReturn(List.of());
        when(versionRepo.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.reloadMetadata())
                .isInstanceOfSatisfying(TerraScoutException.class,
                        e -> assertThat(e.getError())
                                .isEqualTo(TerraScoutError.SDK_SOURCE_UNREACHABLE));
    }

    @Test
    void listConvertsUnknownVersionsToEmpty() {
        when(versionRepo.findAll()).thenReturn(List.of());
        assertThat(service.list(LanguageEnum.JAVA, OsTypeEnum.WINDOWS, ArchEnum.AMD64)).isEmpty();
    }

    @Test
    void listFallsBackToNoInstalledState() {
        SdkVersion v = version("21.0.2");
        v.setId(UUID.randomUUID().toString());
        when(versionRepo.findAll()).thenReturn(List.of(v));
        when(installRepo.findAll()).thenReturn(List.of());
        List<Map<String, Object>> items = service.list(null, null, null);
        assertThat((Boolean) items.get(0).get("installed")).isFalse();
        // 未装条目 installPath = 标准目的路径
        assertThat((String) items.get(0).get("installPath"))
                .isEqualTo(PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "21.0.2").toString());
    }

    @Test
    void listSortsVersionsAscending() {
        // 排布统一：低版本 → 高版本（与入库存放顺序无关）
        when(versionRepo.findAll()).thenReturn(List.of(
                version("21.0.2"), version("17.0.9"), version("11.0.30")));
        when(installRepo.findAll()).thenReturn(List.of());

        List<Map<String, Object>> items = service.list(
                LanguageEnum.JAVA, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
        assertThat(items).extracting(item -> (String) item.get("version"))
                .containsExactly("11.0.30", "17.0.9", "21.0.2");
    }

    @Test
    void listHidesStaleJavaCatalogAndSystemEntries() {
        // 过老 JAVA（major < 11）catalog 行与系统合成项均不可见
        when(versionRepo.findAll()).thenReturn(List.of(version("1.8.0_504"), version("17.0.9")));
        when(installRepo.findAll()).thenReturn(List.of());
        when(sdkProber.probeVersions()).thenReturn(Map.of(
                LanguageEnum.JAVA, Map.of("1.8.0_504", "C:\\dev\\jdk8", "17.0.9", "D:\\dev\\jdk")));

        List<Map<String, Object>> items = service.list(
                LanguageEnum.JAVA, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
        assertThat(items).extracting(item -> (String) item.get("version"))
                .containsExactly("17.0.9");
    }

    @Test
    void reloadMetadataPurgesStaleJava() {
        when(catalogFetcher.fetchAll()).thenReturn(new CatalogFetchResult(
                Map.of(LanguageEnum.JAVA, List.of(catalogEntry(LanguageEnum.JAVA, "17.0.20.1"))),
                Map.of()));
        when(metadataStore.upsertAll(anyList())).thenReturn(1);
        when(versionRepo.findAll()).thenReturn(List.of(version("1.8.0_504"), version("17.0.9")));

        Map<String, Object> result = service.reloadMetadata();

        assertThat((Integer) result.get("purgedStaleJava")).isEqualTo(1);
        verify(versionRepo).deleteAll(argThat((List<SdkVersion> stale) ->
                stale.size() == 1 && "1.8.0_504".equals(stale.get(0).getVersion())));
    }

    @Test
    void listMarksInstalledFromSystemProbe() {
        when(versionRepo.findAll()).thenReturn(List.of(version("17.0.9")));
        when(installRepo.findAll()).thenReturn(List.of());
        when(sdkProber.probeVersions()).thenReturn(Map.of(
                LanguageEnum.JAVA, Map.of("17.0.9", "D:\\dev\\jdk\\jdk-17.0.9")));

        List<Map<String, Object>> items = service.list(
                LanguageEnum.JAVA, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
        assertThat((Boolean) items.get(0).get("installed")).isTrue();
        assertThat((Boolean) items.get(0).get("systemInstalled")).isTrue();
        assertThat((String) items.get(0).get("installedPath")).isEqualTo("D:\\dev\\jdk\\jdk-17.0.9");
        assertThat(items.get(0).containsKey("recordId")).isFalse();
    }

    @Test
    void listSynthesizesSystemEntryWhenCatalogEmpty() {
        when(versionRepo.findAll()).thenReturn(List.of());
        when(installRepo.findAll()).thenReturn(List.of());
        when(sdkProber.probeVersions()).thenReturn(Map.of(
                LanguageEnum.JAVA, Map.of("17.0.9", "D:\\dev\\jdk\\jdk-17.0.9")));

        List<Map<String, Object>> items = service.list(
                LanguageEnum.JAVA, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
        assertThat(items).hasSize(1);
        assertThat((String) items.get(0).get("language")).isEqualTo("JAVA");
        assertThat((String) items.get(0).get("version")).isEqualTo("17.0.9");
        assertThat((Boolean) items.get(0).get("installed")).isTrue();
        assertThat((Boolean) items.get(0).get("systemInstalled")).isTrue();
        assertThat((String) items.get(0).get("installedPath")).isEqualTo("D:\\dev\\jdk\\jdk-17.0.9");
    }

    @Test
    void listFiltersLanguageAcrossCatalogAndSystemProbe() {
        // 回归:切语言后不得混入其他语言的 catalog 或系统探测项
        SdkVersion nodeVersion = version("20.11.0");
        nodeVersion.setLanguage(LanguageEnum.NODE);
        when(versionRepo.findAll()).thenReturn(List.of(version("17.0.9"), nodeVersion));
        when(installRepo.findAll()).thenReturn(List.of());
        when(sdkProber.probeVersions()).thenReturn(Map.of(
                LanguageEnum.JAVA, Map.of("17.0.11", "D:\\dev\\jdk"),
                LanguageEnum.NODE, Map.of("20.11.0", "D:\\dev\\node")));

        List<Map<String, Object>> javaItems = service.list(
                LanguageEnum.JAVA, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
        assertThat(javaItems).allSatisfy(item ->
                assertThat((String) item.get("language")).isEqualTo("JAVA"));
        assertThat(javaItems).anySatisfy(item ->
                assertThat((String) item.get("installedPath")).isEqualTo("D:\\dev\\jdk"));

        List<Map<String, Object>> nodeItems = service.list(
                LanguageEnum.NODE, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
        assertThat(nodeItems).hasSize(1);
        assertThat((String) nodeItems.get(0).get("language")).isEqualTo("NODE");
        assertThat((String) nodeItems.get(0).get("installedPath")).isEqualTo("D:\\dev\\node");
    }

    @Test
    void recordWinsOverSystemForPathAndUninstall() {
        when(versionRepo.findAll()).thenReturn(List.of(version("17.0.9")));
        SdkInstallRecord rec = new SdkInstallRecord();
        rec.setId("r9");
        rec.setLanguage(LanguageEnum.JAVA);
        rec.setVersion("17.0.9");
        rec.setInstallPath("C:\\managed\\sdks\\java\\17.0.9");
        rec.setStatus(InstallStatusEnum.SUCCESS);
        when(installRepo.findAll()).thenReturn(List.of(rec));
        when(sdkProber.probeVersions()).thenReturn(Map.of(
                LanguageEnum.JAVA, Map.of("17.0.9", "D:\\dev\\jdk")));

        List<Map<String, Object>> items = service.list(
                LanguageEnum.JAVA, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
        assertThat((Boolean) items.get(0).get("installed")).isTrue();
        assertThat((Boolean) items.get(0).get("systemInstalled")).isFalse();
        assertThat((String) items.get(0).get("recordId")).isEqualTo("r9");
        assertThat((String) items.get(0).get("installedPath")).isEqualTo("C:\\managed\\sdks\\java\\17.0.9");
    }
}
