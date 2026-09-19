package com.terrascout.orchestrator.app.unit;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.service.AiConnectivityTester;
import com.terrascout.orchestrator.app.service.AiConnectivityTester.AiTestResult;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 连通性测试单测（离线 MockWebServer 驱动）。
 *
 * <p>覆盖：成功 2xx、服务端 401/500、目标不可达、地址非法，以及请求的
 * 方法/路径/鉴权头/载荷形状约束。
 */
class AiConnectivityTesterTest {

    private static final String API_KEY = "sk-test-0123456789abcdef";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiConnectivityTester tester = new AiConnectivityTester(objectMapper);
    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void successReturnsOkAndProperRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        AiTestResult result = tester.test(server.url("/").toString(), API_KEY, "deepseek-chat");
        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isNotBlank();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(recorded.getHeader("Content-Type")).contains("application/json");
        String payload = recorded.getBody().readUtf8();
        assertThat(payload).contains("\"model\":\"deepseek-chat\"", "\"ping\"", "\"max_tokens\"");
    }

    @Test
    void http401ReturnsFailWithStatus() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Invalid API key\"}}"));
        AiTestResult result = tester.test(server.url("/").toString(), API_KEY, "deepseek-chat");
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("401");
    }

    @Test
    void longErrorBodyIsAbbreviated() throws Exception {
        String longBody = "x".repeat(2000);
        server.enqueue(new MockResponse().setResponseCode(500).setBody(longBody));
        AiTestResult result = tester.test(server.url("/").toString(), API_KEY, "deepseek-chat");
        assertThat(result.ok()).isFalse();
        assertThat(result.message().length()).isLessThanOrEqualTo(220);
    }

    @Test
    void unreachableHostReturnsNetworkError() {
        AiTestResult result = tester.test("http://127.0.0.1:1", API_KEY, "deepseek-chat");
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("网络错误");
    }

    @Test
    void malformedUrlReturnsFormatError() {
        AiTestResult result = tester.test("://bad-address", API_KEY, "deepseek-chat");
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("地址格式非法");
    }

    @Test
    void trailingSlashBaseUrlIsNormalized() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        AiTestResult result = tester.test(server.url("/").toString() + "/", API_KEY, "glm-4-flash");
        assertThat(result.ok()).isTrue();
    }
}
