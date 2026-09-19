package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

/**
 * AI 接入连通性测试。
 *
 * <p>以最小代价探测一次真实对话补全接口：发送单条短消息、请求至多 1 个补全 token。
 * 通过即说明地址、鉴权与模型名均可用；失败回传可展示的原因。
 * 请求头与响应体不写日志；失败原因截断展示，避免泄露远端明细。
 */
@Service
public class AiConnectivityTester {

    /** 连接超时（5 秒）。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 单请求总超时（10 秒）。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 失败原因最长的展示长度。 */
    static final int REASON_LIMIT = 200;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiConnectivityTester(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 连通性结果：ok 恒为结果语义（失败非异常）；message 为可展示原因；latencyMs 为耗时。 */
    public record AiTestResult(boolean ok, String message, long latencyMs) {
    }

    /** 对给定配置发起一次连通性探测。 */
    public AiTestResult test(String baseUrl, String apiKey, String model) {
        long start = System.nanoTime();
        try {
            String safeBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            HttpRequest request = HttpRequest.newBuilder(URI.create(safeBase + "/chat/completions"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload(model)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = elapsedMs(start);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new AiTestResult(true, "连接成功", latencyMs);
            }
            return new AiTestResult(false, "HTTP " + response.statusCode() + ": " + abbreviate(response.body()),
                    latencyMs);
        } catch (IllegalArgumentException e) {
            return new AiTestResult(false, "地址格式非法: " + abbreviate(e.getMessage()), elapsedMs(start));
        } catch (IOException e) {
            return new AiTestResult(false, "网络错误: " + abbreviate(e.getMessage()), elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AiTestResult(false, "探测被中断", elapsedMs(start));
        }
    }

    private String payload(String model) {
        try {
            return objectMapper.writeValueAsString(payloadOf(model));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AI 探测请求序列化失败", e);
        }
    }

    /** 固定字段顺序的最小探测载荷。 */
    private static java.util.Map<String, Object> payloadOf(String model) {
        java.util.LinkedHashMap<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("model", model);
        root.put("messages", java.util.List.of(
                java.util.Map.of("role", "user", "content", "ping")));
        root.put("max_tokens", 1);
        return root;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 截断到展示上限，去掉换行以保单行文案。 */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replaceAll("\\r?\\n", " ").trim();
        return singleLine.length() <= REASON_LIMIT ? singleLine : singleLine.substring(0, REASON_LIMIT);
    }
}
