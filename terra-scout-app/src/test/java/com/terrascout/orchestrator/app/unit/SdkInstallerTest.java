package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.service.SdkInstallListener;
import com.terrascout.orchestrator.app.service.SdkInstaller;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.enums.InstallStatusEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.ScopeEnum;
import com.terrascout.orchestrator.download.ArchiveExtractor;
import com.terrascout.orchestrator.download.DownloadResult;
import com.terrascout.orchestrator.download.Downloader;
import com.terrascout.orchestrator.download.ProgressListener;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDK 安装器单测（全局仓库物理落位）：幂等复用 / 真实下载 / 项目级回滚。
 */
class SdkInstallerTest {

    private final SdkInstallRecordRepository repository = mock(SdkInstallRecordRepository.class);
    private final Downloader downloader = mock(Downloader.class);
    private final ArchiveExtractor extractor = mock(ArchiveExtractor.class);
    private final SdkInstaller installer = new SdkInstaller(repository, downloader, extractor);

    @TempDir
    Path dataRoot;

    @BeforeEach
    void setUp() {
        System.setProperty(PathConstants.DATA_DIR_PROPERTY, dataRoot.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(PathConstants.DATA_DIR_PROPERTY);
    }

    private SdkVersion version() {
        SdkVersion v = new SdkVersion();
        v.setLanguage(LanguageEnum.JAVA);
        v.setVersion("17.0.9");
        v.setDownloadUrl("https://example.com/jdk.zip");
        v.setSha256("b".repeat(64));
        return v;
    }

    private SdkInstallRecord record(String id, String projectId, String installPath) {
        SdkInstallRecord r = new SdkInstallRecord();
        r.setId(id);
        r.setLanguage(LanguageEnum.JAVA);
        r.setVersion("17.0.9");
        r.setInstallPath(installPath);
        r.setScope(projectId == null ? ScopeEnum.GLOBAL : ScopeEnum.PROJECT);
        r.setProjectId(projectId);
        r.setStatus(InstallStatusEnum.SUCCESS);
        return r;
    }

    @Test
    void ensureInstalledReusesExisting() {
        SdkInstallRecord existing = record("r1", null, "C:\\fake");
        when(repository.findByLanguageAndVersionAndStatus(
                eq(LanguageEnum.JAVA), eq("17.0.9"), eq(InstallStatusEnum.SUCCESS)))
                .thenReturn(List.of(existing));

        SdkInstallRecord result = installer.ensureInstalled(version(), ScopeEnum.PROJECT, "p1");
        assertThat(result.getId()).isEqualTo("r1");
        verify(downloader, never()).download(any(), any(), any(), any(), any());
        verify(repository, never()).save(any(SdkInstallRecord.class));
    }

    @Test
    void ensureInstalledDownloadsAndRecords() throws IOException {
        when(repository.findByLanguageAndVersionAndStatus(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any(SdkInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        // 真实流程中目标目录由解压器创建；单测 mock 解压器，手动模拟落位
        Path home = PathConstants.sdkHome(dataRoot, LanguageEnum.JAVA, "17.0.9");
        Files.createDirectories(home);

        SdkInstallRecord result = installer.ensureInstalled(version(), ScopeEnum.PROJECT, "p1");
        assertThat(result.getStatus()).isEqualTo(InstallStatusEnum.SUCCESS);
        assertThat(result.getChecksum()).isEqualTo("b".repeat(64));
        assertThat(result.getInstallPath()).isEqualTo(home.toString());
        verify(downloader).download(eq("https://example.com/jdk.zip"), eq("b".repeat(64)),
                any(), any(), any());
        verify(extractor).extract(any(), any());
        verify(repository).save(any(SdkInstallRecord.class));
        // 落位票根护栏凭据：所有新装（含标准仓库）均写出，供卸载物理删除识别
        assertThat(home.resolve(PathConstants.FILE_SDK_MARKER)).exists();
    }

    @Test
    void ensureInstalledUsesCustomHomeAndWritesMarker() throws IOException {
        when(repository.findByLanguageAndVersionAndStatus(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any(SdkInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        Path custom = dataRoot.resolve("picked").resolve("java").resolve("17.0.9");
        Files.createDirectories(custom);

        SdkInstallRecord result = installer.ensureInstalled(version(), ScopeEnum.PROJECT, "p1",
                custom, SdkInstallListener.NOOP);

        assertThat(result.getInstallPath()).isEqualTo(custom.toString());
        verify(extractor).extract(any(), eq(custom));
        assertThat(custom.resolve(PathConstants.FILE_SDK_MARKER)).exists();
    }

    @Test
    void reportsStagesAndDownloadProgressThroughListener() {
        when(repository.findByLanguageAndVersionAndStatus(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any(SdkInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            ProgressListener progress = inv.getArgument(4);
            progress.onProgress(1000L, 500L);
            return new DownloadResult(1000L, 500L, false);
        }).when(downloader).download(any(), any(), any(), any(), any());
        List<String> stages = new ArrayList<>();
        List<long[]> progressCalls = new ArrayList<>();
        SdkInstallListener listener = new SdkInstallListener() {
            @Override
            public void onStage(String stage, String message) {
                stages.add(stage);
            }

            @Override
            public void onProgress(long totalBytes, long bytesDownloaded) {
                progressCalls.add(new long[]{totalBytes, bytesDownloaded});
            }
        };

        installer.ensureInstalled(version(), ScopeEnum.PROJECT, "p1", listener);

        assertThat(stages).containsExactly("STARTING", "DOWNLOADING", "EXTRACTING", "FINISHING", "DONE");
        assertThat(progressCalls).hasSize(1);
        assertThat(progressCalls.get(0)).containsExactly(1000L, 500L);
    }

    @Test
    void substitutesCatalogSizeWhenHttpLengthUnknown() {
        when(repository.findByLanguageAndVersionAndStatus(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any(SdkInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            ProgressListener progress = inv.getArgument(4);
            progress.onProgress(0L, 700L);
            return new DownloadResult(700L, 700L, false);
        }).when(downloader).download(any(), any(), any(), any(), any());
        List<long[]> progressCalls = new ArrayList<>();
        SdkInstallListener listener = new SdkInstallListener() {
            @Override
            public void onProgress(long totalBytes, long bytesDownloaded) {
                progressCalls.add(new long[]{totalBytes, bytesDownloaded});
            }
        };
        SdkVersion withSize = version();
        withSize.setSizeBytes(2000L);

        installer.ensureInstalled(withSize, ScopeEnum.PROJECT, "p1", listener);

        assertThat(progressCalls).hasSize(1);
        // HTTP 长度未知（total=0）时用目录 sizeBytes 估算，保证百分比持续推进
        assertThat(progressCalls.get(0)).containsExactly(2000L, 700L);
    }

    @Test
    void keepsHttpLengthWhenKnownOverCatalogSize() {
        when(repository.findByLanguageAndVersionAndStatus(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any(SdkInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            ProgressListener progress = inv.getArgument(4);
            progress.onProgress(1000L, 500L);
            return new DownloadResult(1000L, 500L, false);
        }).when(downloader).download(any(), any(), any(), any(), any());
        List<long[]> progressCalls = new ArrayList<>();
        SdkInstallListener listener = new SdkInstallListener() {
            @Override
            public void onProgress(long totalBytes, long bytesDownloaded) {
                progressCalls.add(new long[]{totalBytes, bytesDownloaded});
            }
        };
        SdkVersion withSize = version();
        withSize.setSizeBytes(2000L);

        installer.ensureInstalled(withSize, ScopeEnum.PROJECT, "p1", listener);

        // 真实 Content-Length 存在时优先真实值，不被估算值覆盖
        assertThat(progressCalls.get(0)).containsExactly(1000L, 500L);
    }

    @Test
    void cancellationAbortsAtStageBoundaryAndCleansResiduals() throws Exception {
        when(repository.findByLanguageAndVersionAndStatus(any(), any(), any())).thenReturn(List.of());
        // 预置目标落位目录残留（半解压场景），取消后必须被整体清除
        Path home = dataRoot.resolve("sdks").resolve("java").resolve("17.0.9");
        Files.createDirectories(home);
        Files.write(home.resolve("residual.bin"), new byte[]{1, 2, 3});
        AtomicBoolean cancelled = new AtomicBoolean(false);
        doAnswer(inv -> {
            // 下载「完成」后才置取消信号：命中下载后、解压前的阶段边界检查点
            Path archive = inv.getArgument(2);
            Files.write(archive, new byte[]{1, 2, 3});
            Files.write(archive.resolveSibling(archive.getFileName() + ".part"), new byte[]{4});
            cancelled.set(true);
            return new DownloadResult(3L, 3L, false);
        }).when(downloader).download(any(), any(), any(), any(), any());
        SdkInstallListener listener = new SdkInstallListener() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };

        assertThatThrownBy(() -> installer.ensureInstalled(version(), ScopeEnum.PROJECT, "p1", listener))
                .isInstanceOf(CancellationException.class);

        // 取消红线：解压与写记录均不发生，.zip/.part/半解压目录全部即时回收
        verify(extractor, never()).extract(any(), any());
        verify(repository, never()).save(any(SdkInstallRecord.class));
        Path archive = dataRoot.resolve(".downloads").resolve("java-17.0.9.zip");
        assertThat(Files.exists(archive)).isFalse();
        assertThat(Files.exists(archive.resolveSibling("java-17.0.9.zip.part"))).isFalse();
        assertThat(Files.exists(home)).isFalse();
    }

    @Test
    void forwardsCancellationToDownloader() {
        when(repository.findByLanguageAndVersionAndStatus(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any(SdkInstallRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        ProgressListener[] captured = new ProgressListener[1];
        doAnswer(inv -> {
            captured[0] = inv.getArgument(4);
            return new DownloadResult(1000L, 500L, false);
        }).when(downloader).download(any(), any(), any(), any(), any());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        SdkInstallListener listener = new SdkInstallListener() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };

        installer.ensureInstalled(version(), ScopeEnum.PROJECT, "p1", listener);

        // 适配器实时转发取消信号：未取消为 false，置位后下载器立即感知
        assertThat(captured[0].isCancelled()).isFalse();
        cancelled.set(true);
        assertThat(captured[0].isCancelled()).isTrue();
    }

    @Test
    void rollbackDeletesOnlyMatchingProjectRecord() throws Exception {
        Path physical = dataRoot.resolve("sdks").resolve("17.0.9");
        Files.createDirectories(physical);
        SdkInstallRecord project = record("r1", "p1", physical.toString());
        SdkInstallRecord global = record("r2", null, "C:\\global");
        when(repository.findByLanguageAndVersionAndStatus(
                eq(LanguageEnum.JAVA), eq("17.0.9"), eq(InstallStatusEnum.SUCCESS)))
                .thenReturn(List.of(project, global));

        installer.rollback(LanguageEnum.JAVA, "17.0.9", "p1");
        verify(repository).delete(project);
        verify(repository, never()).delete(global);
        assertThat(Files.exists(physical)).isFalse();
    }

    @Test
    void rollbackSkipsWhenNoProjectMatch() {
        SdkInstallRecord global = record("r2", null, "C:\\global");
        when(repository.findByLanguageAndVersionAndStatus(any(), any(), any()))
                .thenReturn(List.of(global));
        installer.rollback(LanguageEnum.JAVA, "17.0.9", "p1");
        verify(repository, never()).delete(any(SdkInstallRecord.class));
    }
}
