package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.terrascout.orchestrator.app.service.catalog.JavaHttpCatalogHttpClient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JDK 目录 HTTP 客户端单测：2xx 透传 / 非 2xx 抛错 / 白名单拒绝 / 中断包装 / 连接类故障重试。
 */
class JavaHttpCatalogHttpClientTest {

    private static final String ALLOWED_URL = "https://go.dev/dl/go1.22.4.windows-amd64.zip";

    @Test
    void returnsBodyFor2xx() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = okResponse(200, "body");
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler())).thenReturn(response);

        assertThat(new JavaHttpCatalogHttpClient(httpClient).get(ALLOWED_URL)).isEqualTo("body");
    }

    @Test
    void throwsForNon2xx() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = okResponse(404, "missing");
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler())).thenReturn(response);

        assertThatThrownBy(() -> new JavaHttpCatalogHttpClient(httpClient).get(ALLOWED_URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void rejectsUrlOutsideWhitelist() {
        HttpClient httpClient = mock(HttpClient.class);
        assertThatThrownBy(() -> new JavaHttpCatalogHttpClient(httpClient)
                .get("http://evil.example.com/payload.zip"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void wrapsInterruptedException() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler()))
                .thenThrow(new InterruptedException("interrupted"));

        assertThatThrownBy(() -> new JavaHttpCatalogHttpClient(httpClient).get(ALLOWED_URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("中断");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void retriesOnceOnConnectTimeoutThenSucceeds() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler()))
                .thenThrow(new HttpConnectTimeoutException("connect timed out"))
                .thenReturn(okResponse(200, "body"));

        assertThat(new JavaHttpCatalogHttpClient(httpClient).get(ALLOWED_URL)).isEqualTo("body");
        verify(httpClient, times(2)).send(any(HttpRequest.class), anyBodyHandler());
    }

    @Test
    void givesUpAfterTwoConnectFailures() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler()))
                .thenThrow(new HttpConnectTimeoutException("connect timed out"))
                .thenThrow(new HttpConnectTimeoutException("connect timed out"));

        assertThatThrownBy(() -> new JavaHttpCatalogHttpClient(httpClient).get(ALLOWED_URL))
                .isInstanceOf(HttpConnectTimeoutException.class);
        verify(httpClient, times(2)).send(any(HttpRequest.class), anyBodyHandler());
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> okResponse(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<String> anyBodyHandler() {
        return any(HttpResponse.BodyHandler.class);
    }
}
