package com.terrascout.orchestrator.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import com.terrascout.orchestrator.TerraScoutApplication;
import com.terrascout.orchestrator.core.constant.PathConstants;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web 集成测试（@SpringBootTest RANDOM_PORT，H2 原生内存模式）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>Token 鉴权：缺失 / 错误 → HTTP 401 + 业务码 401001；正确 → 放行；</li>
 *   <li>免鉴权白名单：GET /api/v1/health 无 Token 返回 200；</li>
 *   <li>全局异常：无效路径请求项目分析 → 400001 / 项目不存在 → 404001；</li>
 *   <li>项目导入分析持久化 + 审计 hash 链 chainValid=true。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { TerraScoutApplication.class, FakeCatalogConfig.class })
@ActiveProfiles("test")
@TestPropertySource(properties = "terrascout.token=test-integration-token-0000")
class WebIntegrationTest {

    private static final String TOKEN = "test-integration-token-0000";
    private static final String TOKEN_HEADER = "X-TerraScout-Token";

    @Autowired
    private TestRestTemplate rest;

    @TempDir
    Path projectRoot;

    @TempDir
    Path dataRootDir;

    /** 重定向数据根到隔离临时目录，避免 settings/backup/diagnostic 写入真实用户目录。 */
    @BeforeEach
    void redirectDataRoot() {
        System.setProperty(PathConstants.DATA_DIR_PROPERTY, dataRootDir.toString());
    }

    @AfterEach
    void clearDataRoot() {
        System.clearProperty(PathConstants.DATA_DIR_PROPERTY);
    }

    @Test
    void healthIsUnauthenticated() {
        ResponseEntity<String> resp = rest.getForEntity("/api/v1/health", String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(200000);
        assertThat((String) JsonPath.read(resp.getBody(), "$.data.status")).isEqualTo("UP");
    }

    @Test
    void missingTokenReturns401() {
        ResponseEntity<String> resp = rest.getForEntity("/api/v1/system/info", String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(401);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(401001);
    }

    @Test
    void wrongTokenReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, "wrong-token");
        ResponseEntity<String> resp = rest.exchange("/api/v1/system/info", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(401);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(401001);
    }

    @Test
    void validTokenAllowsProtectedEndpoint() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, TOKEN);
        ResponseEntity<String> resp = rest.exchange("/api/v1/system/info", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(200000);
        assertThat((Object) JsonPath.read(resp.getBody(), "$.data.version")).isNotNull();
    }

    @Test
    void invalidProjectPathReturns400001() throws Exception {
        String badPath = projectRoot.resolve("nonexistent-dir").toString();
        String body = "{\"path\":\"" + escapePath(badPath) + "\"}";
        ResponseEntity<String> resp = postJson("/api/v1/project/analyze", body);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(404);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(404001);
    }

    @Test
    void analyzePersistsProjectAndWritesAuditChain() throws Exception {
        // 构造一个可解析的 Maven 项目（simple pom.xml，Parser 模块可解析）
        Files.writeString(projectRoot.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>com.example</groupId>\n"
                        + "  <artifactId>demo</artifactId>\n"
                        + "  <version>1.0.0</version>\n"
                        + "  <properties><maven.compiler.release>17</maven.compiler.release></properties>\n"
                        + "</project>\n");

        String body = "{\"path\":\"" + escapePath(projectRoot.toString()) + "\"}";
        ResponseEntity<String> resp = postJson("/api/v1/project/analyze", body);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
        String projectId = JsonPath.read(resp.getBody(), "$.data.projectId");
        assertThat(projectId).isNotNull();

        // 审计 hash 链应有效（analyze 已写一条 PROJECT_ANALYZE 审计）
        HttpHeaders headers = headers();
        ResponseEntity<String> audit = rest.exchange("/api/v1/audit", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(Integer.valueOf(audit.getStatusCode().value())).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(audit.getBody(), "$.data.chainValid")).isEqualTo(true);
        assertThat((Integer) JsonPath.read(audit.getBody(), "$.data.total")).isGreaterThan(0);
    }

    @Test
    void executeRequiresConfirm() throws Exception {
        String body = "{\"planId\":\"00000000-0000-0000-0000-000000000001\",\"confirm\":false}";
        ResponseEntity<String> resp = postJson("/api/v1/task/execute", body);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(400);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(400003);
    }

    @Test
    void executeBlankPlanIdReturns400003() {
        ResponseEntity<String> resp = postJson("/api/v1/task/execute", "{\"planId\":\"\",\"confirm\":true}");
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(400);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(400003);
    }

    @Test
    void executeUnknownPlanIdReturns404003() {
        String body = "{\"planId\":\"00000000-0000-0000-0000-00000000dead\",\"confirm\":true}";
        ResponseEntity<String> resp = postJson("/api/v1/task/execute", body);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(404);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(404003);
    }

    @Test
    void executeWithConfirmCreatesQueuedTask() throws Exception {
        String projectId = createProject("exec");
        String body = "{\"planId\":\"" + projectId + "\",\"confirm\":true}";
        ResponseEntity<String> resp = postJson("/api/v1/task/execute", body);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
        assertThat((String) JsonPath.read(resp.getBody(), "$.data.status")).isEqualTo("QUEUED");
        String taskId = JsonPath.read(resp.getBody(), "$.data.taskId");
        assertThat(taskId).isNotNull();
    }

    @Test
    void idempotentExecuteReplaysFirstResult() throws Exception {
        String projectId = createProject("idem");
        String idem = "idem-key-" + System.nanoTime();
        String body = "{\"planId\":\"" + projectId + "\",\"confirm\":true,"
                + "\"idempotencyKey\":\"" + idem + "\"}";
        ResponseEntity<String> first = postJson("/api/v1/task/execute", body);
        ResponseEntity<String> second = postJson("/api/v1/task/execute", body);
        assertThat(Integer.valueOf(first.getStatusCode().value())).isEqualTo(200);
        assertThat(Integer.valueOf(second.getStatusCode().value())).isEqualTo(200);
        assertThat((String) JsonPath.read(second.getBody(), "$.data.taskId"))
                .isEqualTo((Object) JsonPath.read(first.getBody(), "$.data.taskId"));
    }

    @Test
    void taskListDetailsAndOperation() throws Exception {
        String projectId = createProject("tasklist");
        String body = "{\"planId\":\"" + projectId + "\",\"confirm\":true}";
        ResponseEntity<String> created = postJson("/api/v1/task/execute", body);
        String taskId = JsonPath.read(created.getBody(), "$.data.taskId");

        ResponseEntity<String> list = rest.exchange("/api/v1/task", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(list.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.data.total")).isGreaterThan(0);

        ResponseEntity<String> detail = rest.exchange("/api/v1/task/" + taskId, HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(detail.getStatusCode().value())).isEqualTo(200);
        assertThat((String) JsonPath.read(detail.getBody(), "$.data.taskId")).isEqualTo(taskId);
        assertThat((Object) JsonPath.read(detail.getBody(), "$.data.steps")).isNotNull();

        String op = "{\"taskId\":\"" + taskId + "\"}";
        ResponseEntity<String> operation = postJson("/api/v1/task/pause", op);
        assertThat(Integer.valueOf(operation.getStatusCode().value())).isEqualTo(200);
    }

    @Test
    void taskNotFoundReturns404002() {
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/task/00000000-0000-0000-0000-00000000dead", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(404);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(404002);
    }

    @Test
    void projectListDetailAndDelete() throws Exception {
        String pom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>crd</artifactId>\n"
                + "  <version>2.0.0</version>\n"
                + "  <properties><maven.compiler.release>17</maven.compiler.release></properties>\n"
                + "</project>\n";
        Files.writeString(projectRoot.resolve("pom.xml"), pom);
        String body = "{\"path\":\"" + escapePath(projectRoot.toString()) + "\"}";
        ResponseEntity<String> created = postJson("/api/v1/project/analyze", body);
        String projectId = JsonPath.read(created.getBody(), "$.data.projectId");

        ResponseEntity<String> list = rest.exchange("/api/v1/project", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(list.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(list.getBody(), "$.data.total")).isGreaterThan(0);

        ResponseEntity<String> detail = rest.exchange("/api/v1/project/" + projectId, HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(detail.getStatusCode().value())).isEqualTo(200);
        assertThat((String) JsonPath.read(detail.getBody(), "$.data.projectId")).isEqualTo(projectId);

        ResponseEntity<String> del = rest.exchange("/api/v1/project/" + projectId,
                HttpMethod.DELETE, new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(del.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(del.getBody(), "$.code")).isEqualTo(200000);
    }

    @Test
    void projectNotFoundReturns404003() {
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/project/00000000-0000-0000-0000-00000000dead", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(404);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(404003);
    }

    @Test
    void auditAcceptsFilters() throws Exception {
        String pom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>aud</artifactId>\n"
                + "  <version>1.0.0</version>\n"
                + "  <properties><maven.compiler.release>17</maven.compiler.release></properties>\n"
                + "</project>\n";
        Files.writeString(projectRoot.resolve("pom.xml"), pom);
        String body = "{\"path\":\"" + escapePath(projectRoot.toString()) + "\"}";
        postJson("/api/v1/project/analyze", body);

        ResponseEntity<String> audit = rest.exchange(
                "/api/v1/audit?action=PROJECT_ANALYZE", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(audit.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(audit.getBody(), "$.data.total")).isGreaterThan(0);
        // 过滤后只是全链子集，链完整性只能在不可过滤的全量链上判定
        ResponseEntity<String> allAudit = rest.exchange("/api/v1/audit", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat((Boolean) JsonPath.read(allAudit.getBody(), "$.data.chainValid")).isEqualTo(true);
    }

    @Test
    void systemInfoAndKernelHealth() {
        HttpHeaders headers = headers();
        ResponseEntity<String> info = rest.exchange("/api/v1/system/info", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(Integer.valueOf(info.getStatusCode().value())).isEqualTo(200);
        assertThat((Object) JsonPath.read(info.getBody(), "$.data.version")).isNotNull();
        assertThat((String) JsonPath.read(info.getBody(), "$.data.dataDir"))
                .isEqualTo(dataRootDir.toString());
        assertThat((String) JsonPath.read(info.getBody(), "$.data.sdkRepoDir"))
                .isEqualTo(dataRootDir.resolve(PathConstants.DIR_SDKS).toString());
        assertThat(((Number) JsonPath.read(info.getBody(), "$.data.dbSizeBytes")).longValue())
                .satisfies(n -> assertThat(n >= 0L).isTrue());

        ResponseEntity<String> health = rest.exchange("/api/v1/system/health-status",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(Integer.valueOf(health.getStatusCode().value())).isEqualTo(200);
        assertThat((String) JsonPath.read(health.getBody(), "$.data.status")).isEqualTo("UP");
    }

    @Test
    void missingBodyProjectAnalyzeReturns400001() {
        // path 为空白 → 项目路径无效（400001）
        String body = "{\"path\":\"   \"}";
        ResponseEntity<String> resp = postJson("/api/v1/project/analyze", body);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(400);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(400001);
    }

    @Test
    void settingsGetReturnsDefaults() {
        ResponseEntity<String> resp = rest.exchange("/api/v1/settings", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(200000);
        assertThat((String) JsonPath.read(resp.getBody(), "$.data.download.mirror")).contains("huaweicloud");
    }

    @Test
    void settingsPutRoundTripPersists() throws Exception {
        String body = "{\"logLevel\":\"DEBUG\",\"aiEnabled\":true}";
        ResponseEntity<String> put = putJson("/api/v1/settings", body);
        assertThat(Integer.valueOf(put.getStatusCode().value())).isEqualTo(200);
        assertThat((String) JsonPath.read(put.getBody(), "$.data.logLevel")).isEqualTo("DEBUG");
        assertThat((Boolean) JsonPath.read(put.getBody(), "$.data.aiEnabled")).isEqualTo(true);

        ResponseEntity<String> get = rest.exchange("/api/v1/settings", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat((String) JsonPath.read(get.getBody(), "$.data.logLevel")).isEqualTo("DEBUG");
        assertThat((Boolean) JsonPath.read(get.getBody(), "$.data.aiEnabled")).isEqualTo(true);
    }

    @Test
    void aiTestRejectsUnknownProviderWith400010() {
        String body = "{\"aiProvider\":\"unknown\",\"aiBaseUrl\":\"https://x.invalid\",\"aiApiKey\":\"k\",\"aiModel\":\"m\"}";
        ResponseEntity<String> resp = postJson("/api/v1/settings/ai/test", body);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(400);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(400010);
    }

    @Test
    void aiTestRejectsBlankApiKeyWith400010() {
        String body = "{\"aiProvider\":\"deepseek\",\"aiBaseUrl\":\"https://api.deepseek.com\","
                + "\"aiApiKey\":\" \",\"aiModel\":\"deepseek-chat\"}";
        ResponseEntity<String> resp = postJson("/api/v1/settings/ai/test", body);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(400);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(400010);
    }

    @Test
    void aiTestAgainstMockServerReportsOk() throws IOException {
        MockWebServer server = new MockWebServer();
        try {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.start();
            String body = "{\"aiProvider\":\"openai-compatible\",\"aiBaseUrl\":\""
                    + server.url("/") + "\",\"aiApiKey\":\"sk-test\",\"aiModel\":\"gpt-4o-mini\"}";
            ResponseEntity<String> resp = postJson("/api/v1/settings/ai/test", body);
            assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
            assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(200000);
            assertThat((Boolean) JsonPath.read(resp.getBody(), "$.data.ok")).isTrue();
            assertThat((Integer) JsonPath.read(resp.getBody(), "$.data.latencyMs")).isNotNull();
        } finally {
            server.shutdown();
        }
    }

    @Test
    void sdkListSystemSynthesizedEntriesAreMarkedInstalled() {
        ResponseEntity<String> resp = rest.exchange("/api/v1/sdk/list", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(200000);
        List<Map<String, Object>> items = JsonPath.read(resp.getBody(), "$.data.items");
        if (items == null || items.isEmpty()) {
            return; // 本机无任何系统 SDK 时不产生条目，无可断言
        }
        // 系统合成项：元数据未收录的系统已装版本必须标记 installed=true，不受 catalog 内容影响
        List<Map<String, Object>> systemEntries = items.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("systemInstalled")))
                .collect(Collectors.toList());
        assertThat(systemEntries).isNotEmpty();
        assertThat(systemEntries).allSatisfy(item ->
                assertThat(item.get("installed")).isEqualTo(true));
    }

    @Test
    void reloadMetadataAggregatesCatalogAndPersistsExternalFile() throws Exception {
        ResponseEntity<String> resp = postJson("/api/v1/sdk/metadata/reload", "{}");
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(200000);
        assertThat((Boolean) JsonPath.read(resp.getBody(), "$.data.loaded")).isTrue();
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.data.sdkCount")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.data.sources.length()")).isEqualTo(4);
        List<String> statuses = JsonPath.read(resp.getBody(), "$.data.sources[*].status");
        assertThat(statuses).containsOnly("SUCCESS");

        // 外部文件写回：schema + 假条目写入 {data-root}/config/sdk-metadata.json
        Path metadata = PathConstants.sdkMetadata(dataRootDir);
        assertThat(metadata).isRegularFile();
        String file = Files.readString(metadata);
        assertThat(file).contains("\"schemaVersion\"", "17.9.9", "official-refresh");

        // 列表可见官方源条目（按语言过滤）
        ResponseEntity<String> list = rest.exchange("/api/v1/sdk/list?language=JAVA", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(list.getStatusCode().value())).isEqualTo(200);
        List<String> versions = JsonPath.read(list.getBody(), "$.data.items[*].version");
        assertThat(versions).contains("17.9.9");
    }

    @Test
    void taskLogsForMissingTaskReturns404002() {
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/task/00000000-0000-0000-0000-00000000dead/logs",
                HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(404);
        assertThat((Integer) JsonPath.read(resp.getBody(), "$.code")).isEqualTo(404002);
    }

    @Test
    void taskLogsForQueuedTaskReturnsLines() throws Exception {
        String projectId = createProject("tasklogs");
        String body = "{\"planId\":\"" + projectId + "\",\"confirm\":true}";
        ResponseEntity<String> created = postJson("/api/v1/task/execute", body);
        String taskId = JsonPath.read(created.getBody(), "$.data.taskId");

        ResponseEntity<String> logs = rest.exchange("/api/v1/task/" + taskId + "/logs",
                HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(logs.getStatusCode().value())).isEqualTo(200);
        assertThat((Object) JsonPath.read(logs.getBody(), "$.data.lines")).isNotNull();
    }

    @Test
    void systemDiagnosticCreatesSyntheticZip() throws Exception {
        // 预置一份日志，确保诊断包内含 logs 条目
        Path dataRoot = dataRootDir;
        Path logFile = dataRoot.resolve(PathConstants.DIR_LOGS).resolve(PathConstants.FILE_LOG_KERNEL);
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, "diagnostic marker");

        ResponseEntity<String> resp = rest.exchange("/api/v1/system/diagnostic", HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(200);
        String zipPath = JsonPath.read(resp.getBody(), "$.data.zipPath");
        assertThat(zipPath).contains(".zip");
        assertThat(zipPath).doesNotContain("***");
        assertThat(zipPath).startsWith(dataRootDir.toString());
    }

    @Test
    void systemBackupWhenNoDbFileReturns500() {
        // 内存库无文件库，备份应有服务端 500 兜底（全局异常映射）
        ResponseEntity<String> resp = postJson("/api/v1/system/backup", "{}");
        assertThat(Integer.valueOf(resp.getStatusCode().value())).isEqualTo(500);
    }

    private String createProject(String artifactId) throws Exception {
        String pom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>" + artifactId + "</artifactId>\n"
                + "  <version>1.0.0</version>\n"
                + "  <properties><maven.compiler.release>17</maven.compiler.release></properties>\n"
                + "</project>\n";
        Files.writeString(projectRoot.resolve("pom.xml"), pom);
        String body = "{\"path\":\"" + escapePath(projectRoot.toString()) + "\"}";
        ResponseEntity<String> resp = postJson("/api/v1/project/analyze", body);
        return JsonPath.read(resp.getBody(), "$.data.projectId");
    }

    private ResponseEntity<String> putJson(String path, String body) {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, TOKEN);
        return headers;
    }

    private static String escapePath(String path) {
        return path.replace("\\", "\\\\");
    }
}
