package com.terrascout.orchestrator.app.config;

import com.terrascout.orchestrator.app.service.ProbeDiagnostics;
import com.terrascout.orchestrator.app.service.SystemSdkProber;
import com.terrascout.orchestrator.app.service.catalog.CatalogHttpClient;
import com.terrascout.orchestrator.app.service.catalog.JavaHttpCatalogHttpClient;
import com.terrascout.orchestrator.download.ArchiveExtractor;
import com.terrascout.orchestrator.download.Downloader;
import com.terrascout.orchestrator.env.EnvInjector;
import com.terrascout.orchestrator.env.EnvScriptGenerator;
import com.terrascout.orchestrator.env.ProcessExecutor;
import com.terrascout.orchestrator.parser.ConstraintExtractor;
import com.terrascout.orchestrator.parser.PomParser;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 应用 bean 装配：将 parser / download / env 模块的领域服务与任务线程池暴露为 Spring bean
 * （app 层唯一装配入口）。
 */
@Configuration
public class BeansConfig {

    /** POM 解析器（parser 模块，单例注入）。 */
    @Bean
    public PomParser pomParser() {
        return new PomParser();
    }

    /** 版本约束提取器（parser 模块）。 */
    @Bean
    public ConstraintExtractor constraintExtractor(PomParser pomParser) {
        return new ConstraintExtractor(pomParser);
    }

    /** 命令执行器（env 模块，D-008 白名单 + 超时）。 */
    @Bean
    public ProcessExecutor processExecutor() {
        return new ProcessExecutor();
    }

    /** 系统级已装 SDK 探测（SDK 管理 installed 语义：识别任意盘符的 JDK/Node/Python/Go，探测降级经 SLF4J 记录）。 */
    @Bean
    public SystemSdkProber systemSdkProber(ProcessExecutor processExecutor) {
        return new SystemSdkProber(processExecutor, ProbeDiagnostics.slf4j());
    }

    /** 进程级环境注入计算（env 模块）。 */
    @Bean
    public EnvInjector envInjector() {
        return new EnvInjector();
    }

    /** env.ps1 脚本生成器（env 模块）。 */
    @Bean
    public EnvScriptGenerator envScriptGenerator() {
        return new EnvScriptGenerator();
    }

    /** 断点续传下载 + SHA-256 校验门面（download 模块）。 */
    @Bean
    public Downloader downloader() {
        return new Downloader();
    }

    /** 归档解压器（download 模块，四重安全检查）。 */
    @Bean
    public ArchiveExtractor archiveExtractor() {
        return new ArchiveExtractor();
    }

    /** SDK 官方源 HTTP 客户端（元数据刷新；JDK HttpClient 零依赖，连接 10s / 请求 30s 超时）。 */
    @Bean
    public CatalogHttpClient catalogHttpClient() {
        return new JavaHttpCatalogHttpClient();
    }

    /** 任务异步执行线程池：承载装配编排（R-024 由 afterCommit 提交任务）。 */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("terra-scout-task-");
        executor.initialize();
        return executor;
    }
}
