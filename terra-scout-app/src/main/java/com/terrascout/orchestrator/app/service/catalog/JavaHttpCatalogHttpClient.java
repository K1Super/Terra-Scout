package com.terrascout.orchestrator.app.service.catalog;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 JDK {@link HttpClient} 的目录客户端（零外部依赖）。
 *
 * <p>连接超时 10 秒、单请求总超时 30 秒（下载超时口径）；
 * 请求前先做 {@link CatalogUrlPolicy} 白名单校验，非 2xx 一律抛 IOException。
 * 强制低版本 HTTP 协议：部分官方源（nodejs.org 等）在代理环境下高版本协议协商不稳定，
 * 实测低版本协议稳定可达。连接类瞬时故障（连接超时/拒绝）自动重试
 * {@value #MAX_ATTEMPTS} 次；非 2xx 与读取超时视为确定性结果，不重试。
 */
public class JavaHttpCatalogHttpClient implements CatalogHttpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaHttpCatalogHttpClient.class);

    /** 连接超时（10 秒）。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 单请求总超时（30 秒）。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 单请求最大尝试次数（连接类瞬时故障才重试）。 */
    static final int MAX_ATTEMPTS = 2;

    private final HttpClient httpClient;

    /** 生产构造：连接 10s + 请求 30s + 跟随重定向 + 低版本 HTTP 协议。 */
    public JavaHttpCatalogHttpClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    /** 测试构造：注入可控底层客户端。 */
    public JavaHttpCatalogHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String get(String url) throws IOException {
        if (!CatalogUrlPolicy.isAllowed(url)) {
            throw new IOException("下载地址不在官方源白名单内: " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "TerraScout/0.1 (SDK catalog refresh)")
                .GET()
                .build();
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    LOGGER.warn("catalog http non-2xx status={} duration={}ms url={}",
                            response.statusCode(), elapsedMs, url);
                    throw new IOException("HTTP " + response.statusCode() + " GET " + url);
                }
                LOGGER.info("catalog http ok status={} bytes={} duration={}ms host={} path={} attempt={}",
                        response.statusCode(), response.body().length(), elapsedMs,
                        request.uri().getHost(), request.uri().getPath(), attempt);
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("目录请求被中断: " + url, e);
            } catch (IOException e) {
                lastFailure = e;
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                if (isTransientConnect(e) && attempt < MAX_ATTEMPTS) {
                    LOGGER.warn("catalog http connect fail duration={}ms host={} path={} error={} "
                            + "attempt={}/{} retrying", elapsedMs, request.uri().getHost(),
                            request.uri().getPath(), e.getMessage(), attempt, MAX_ATTEMPTS);
                    continue;
                }
                LOGGER.warn("catalog http fail duration={}ms host={} path={} error={} attempt={}/{}",
                        elapsedMs, request.uri().getHost(), request.uri().getPath(),
                        e.getMessage(), attempt, MAX_ATTEMPTS);
                break;
            }
        }
        throw lastFailure;
    }

    /** 连接类瞬时故障（连接超时/拒绝）才重试；其余异常视为确定性结果。 */
    private static boolean isTransientConnect(IOException e) {
        return e instanceof HttpConnectTimeoutException || e instanceof ConnectException;
    }
}
