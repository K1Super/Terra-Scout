package com.terrascout.orchestrator.app.service;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 系统 SDK 探测可观测性 SPI（R30，P1-3）：探测策略链的每一处降级都必须产生诊断事件，
 * 禁止"静默消失"——线上某台机器缺 Python 版本时，可通过事件定位到具体命令与失败原因。
 *
 * <ul>
 *   <li>{@code recordProbeFailure} —— 进程异常 / IO 异常（WARN 级）；</li>
 *   <li>{@code recordProbeSuccess} —— 进程完成且耗时（DEBUG 级）；</li>
 *   <li>{@code recordDuplicateVersion} —— 同版本不同 home 冲突（INFO 级）；</li>
 *   <li>{@code recordScanSkipped} —— 目录扫描被跳过（WARN 级）。</li>
 * </ul>
 *
 * <p>默认 {@link #NOOP}（静默）；宿主经 {@link #slf4j()} 桥接 SLF4J 后装配到
 * {@link SystemSdkProber}。
 */
public interface ProbeDiagnostics {

    /** 探测失败（进程异常 / IO 读取异常）：WARN 级。 */
    void recordProbeFailure(String source, String command, Throwable cause);

    /** 探测成功（进程完成，含耗时）：DEBUG 级。 */
    void recordProbeSuccess(String source, String command, long elapsedMs);

    /** 同版本不同 home 冲突：INFO 级，保留先发现者（{@code kept}）。 */
    void recordDuplicateVersion(String source, String version, String kept, String discarded);

    /** 目录扫描被跳过（IO 失败 / 非法根）：WARN 级。 */
    void recordScanSkipped(String source, Path root, Throwable cause);

    /** 默认诊断：静默。 */
    ProbeDiagnostics NOOP = new ProbeDiagnostics() {
        @Override
        public void recordProbeFailure(String source, String command, Throwable cause) {
        }

        @Override
        public void recordProbeSuccess(String source, String command, long elapsedMs) {
        }

        @Override
        public void recordDuplicateVersion(String source, String version, String kept, String discarded) {
        }

        @Override
        public void recordScanSkipped(String source, Path root, Throwable cause) {
        }
    };

    /** SLF4J 桥接工厂：事件即日志（warn/debug/info），生产装配用。 */
    static ProbeDiagnostics slf4j() {
        final Logger log = LoggerFactory.getLogger(SystemSdkProber.class);
        return new ProbeDiagnostics() {
            @Override
            public void recordProbeFailure(String source, String command, Throwable cause) {
                log.warn("SDK 探测降级 source={} command={} cause={}", source, command,
                        cause == null ? "null" : cause.getClass().getSimpleName());
            }

            @Override
            public void recordProbeSuccess(String source, String command, long elapsedMs) {
                log.debug("SDK 探测成功 source={} command={} elapsedMs={}", source, command, elapsedMs);
            }

            @Override
            public void recordDuplicateVersion(String source, String version, String kept, String discarded) {
                log.info("SDK 同版本冲突 source={} version={} kept={} discarded={}",
                        source, version, kept, discarded);
            }

            @Override
            public void recordScanSkipped(String source, Path root, Throwable cause) {
                log.warn("SDK 扫描跳过 source={} root={} cause={}", source, root,
                        cause == null ? "null" : cause.getClass().getSimpleName());
            }
        };
    }
}
