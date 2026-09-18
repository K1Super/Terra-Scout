package com.terrascout.orchestrator.app.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.enums.LanguageEnum;

import org.springframework.stereotype.Component;

/**
 * SDK 安装进度内存存储：异步安装任务的可轮询快照（进程内共享，无需持久化）。
 *
 * <p>写入方为安装工作线程，读取方为进度查询端点；快照字段全部 volatile 且更新互斥，
 * 保证轮询方任何时刻读到一致的状态组合。任务完成后快照保留（供前端终态收尾读取），
 * 由容量上限淘汰最旧条目。
 */
@Component
public class SdkInstallProgressStore {

    /** 单进程最大保留任务数（超出淘汰最旧，防内存膨胀）。 */
    static final int MAX_JOBS = 32;

    private final Map<String, Snapshot> jobs = new ConcurrentHashMap<>();

    /** 注册新任务并返回可写快照（落地路径取 D-004 标准目录）。 */
    public Snapshot open(String jobId, LanguageEnum language, String version) {
        return open(jobId, language, version,
                PathConstants.sdkHome(PathConstants.dataRoot(), language, version).toString());
    }

    /** 注册新任务并返回可写快照（installPath 为服务端校验后确定的落地路径，自定义目录同口径）。 */
    public Snapshot open(String jobId, LanguageEnum language, String version, String installPath) {
        Snapshot snapshot = new Snapshot(jobId, language, version, installPath);
        jobs.put(jobId, snapshot);
        while (jobs.size() > MAX_JOBS) {
            jobs.keySet().stream().findFirst().ifPresent(jobs::remove);
        }
        return snapshot;
    }

    /** 按任务 ID 取快照（不存在返回空）。 */
    public Optional<Snapshot> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /** 安装任务进度快照（可变，写入方独占；读方只读字段快照语义）。 */
    public static final class Snapshot {

        private final String jobId;
        private final LanguageEnum language;
        private final String version;
        /** 安装落地目录（标准仓库或用户所选自定义目录），任务注册即确定，全程对用户可见。 */
        private final String installPath;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String stage = "QUEUED";
        private volatile String message = "任务已排队";
        private volatile long totalBytes;
        private volatile long bytesDownloaded;
        private volatile int percent;
        private volatile boolean finished;
        private volatile boolean success;
        private volatile String recordId;
        private volatile String error;

        private Snapshot(String jobId, LanguageEnum language, String version, String installPath) {
            this.jobId = jobId;
            this.language = language;
            this.version = version;
            this.installPath = installPath;
        }

        /** 阶段流转（stage 为英文常量，message 为用户可读文案）。 */
        public synchronized void stage(String stage, String message) {
            this.stage = stage;
            this.message = message;
        }

        /** 下载字节进度（DOWNLOADING 阶段；total 为 0 时百分比按未知处理保持 0）。 */
        public synchronized void downloadProgress(long totalBytes, long bytesDownloaded) {
            this.totalBytes = totalBytes;
            this.bytesDownloaded = bytesDownloaded;
            if (totalBytes > 0) {
                this.percent = (int) Math.min(99, bytesDownloaded * 100 / totalBytes);
            }
        }

        /** 安装成功终态。 */
        public synchronized void succeed(String recordId) {
            this.stage = "DONE";
            this.message = "安装完成";
            this.percent = 100;
            this.finished = true;
            this.success = true;
            this.recordId = recordId;
        }

        /** 安装失败终态。 */
        public synchronized void fail(String error) {
            this.stage = "FAILED";
            this.message = "安装失败";
            this.finished = true;
            this.success = false;
            this.error = error;
        }

        /**
         * 标记取消信号（用户请求取消）：已终态任务拒绝（由服务端抛 409005）；
         * 置位后工作线程在最近的下一个检查点停止，并负责清理下载暂存
         * （.part / 半解压目录）后调用 {@link #cancelledDone()} 收敛终态。
         */
        public synchronized void requestCancel() {
            cancelled.set(true);
            this.stage = "CANCELLING";
            this.message = "正在停止并清理临时文件…";
        }

        /** 取消是否已请求（供下载循环与阶段检查点查询，volatile 语义由 AtomicBoolean 保证）。 */
        public boolean isCancelled() {
            return cancelled.get();
        }

        /** 任务是否已到终态（DONE / FAILED / CANCELLED）。 */
        public boolean isFinished() {
            return finished;
        }

        /** 取消终态收敛：工作线程完成清理后调用（CANCELLED 必含 cancelled=true）。 */
        public synchronized void cancelledDone(String message) {
            this.cancelled.set(true);
            this.stage = "CANCELLED";
            this.message = message;
            this.finished = true;
            this.success = false;
        }

        /** 快照 → API 响应字段（rest-schema 3.4.18）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jobId", jobId);
            map.put("language", language.name());
            map.put("version", version);
            map.put("stage", stage);
            map.put("message", message);
            map.put("percent", percent);
            map.put("totalBytes", totalBytes);
            map.put("bytesDownloaded", bytesDownloaded);
            map.put("finished", finished);
            map.put("success", success);
            map.put("cancelled", cancelled.get());
            map.put("recordId", recordId);
            map.put("installPath", installPath);
            map.put("error", error);
            return map;
        }
    }
}
