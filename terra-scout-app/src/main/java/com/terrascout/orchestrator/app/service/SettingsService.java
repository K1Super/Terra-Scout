package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.core.constant.PathConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 运行时设置服务。
 *
 * <p>持久化于 {@code {data-root}/config/user-settings.json}，只接受白名单键；
 * 优先级：内置默认 → 文件。文件损坏时回退默认并重建（不允许因坏配置起不来）。
 */
@Service
public class SettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    /** AI 供应商标准模板（默认接入地址 + 推荐模型），接入地址与模型均可自定义覆盖。 */
    public static final Map<String, AiTemplate> AI_TEMPLATES = Map.of(
            "deepseek", new AiTemplate("https://api.deepseek.com", "deepseek-chat"),
            "glm", new AiTemplate("https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            "openai-compatible", new AiTemplate("https://api.openai.com/v1", "gpt-4o-mini"));

    private final ObjectMapper objectMapper;

    public SettingsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取运行时设置（内置默认打底，会话覆盖多余键）。
     * 返回值可能含 {@code aiApiKey}，调用方不得写入日志或诊断明文。
     */
    public Map<String, Object> load() {
        Map<String, Object> merged = defaults();
        Path file = PathConstants.userSettings(PathConstants.dataRoot());
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> loaded = objectMapper.readValue(file.toFile(), objectMapper
                        .getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
                for (Map.Entry<String, Object> entry : loaded.entrySet()) {
                    if (isAcceptedKey(entry.getKey())) {
                        merged.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (IOException e) {
                LOG.warn("user-settings.json 读取失败，回退默认值: {}", file, e);
            }
        }
        return merged;
    }

    /** 保存运行时设置（白名单键 + AI 配置规范化），原子写文件。 */
    public Map<String, Object> save(Map<String, Object> input) {
        Map<String, Object> accepted = defaults();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!isAcceptedKey(key) || value == null) {
                continue;
            }
            if ("aiProvider".equals(key)) {
                String provider = value.toString().trim();
                if (AI_TEMPLATES.containsKey(provider)) {
                    accepted.put(key, provider);
                }
            } else if ("aiBaseUrl".equals(key) || "aiModel".equals(key) || "aiApiKey".equals(key)) {
                accepted.put(key, value.toString().trim());
            } else {
                accepted.put(key, value);
            }
        }
        normalizeAi(accepted);
        Path file = PathConstants.userSettings(PathConstants.dataRoot());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "user-settings", ".json.tmp");
            Files.write(tmp, objectMapper.writeValueAsBytes(accepted));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("user-settings.json 写出失败", e);
        }
        return accepted;
    }

    /**
     * AI 段合规化：baseUrl 去尾部斜杠、空值回落模板默认；模型空值回落模板默认；
     * 没有出现在模板集中的供应商已在保存时被过滤，此处只兜底防御。
     */
    private static void normalizeAi(Map<String, Object> settings) {
        String provider = (String) settings.get("aiProvider");
        AiTemplate template = AI_TEMPLATES.get(provider);
        if (template == null) {
            return;
        }
        String baseUrl = trimToDefault(settings.get("aiBaseUrl"), template.baseUrl());
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        settings.put("aiBaseUrl", baseUrl);
        settings.put("aiModel", trimToDefault(settings.get("aiModel"), template.model()));
    }

    private static String trimToDefault(Object value, String fallback) {
        return value == null || value.toString().trim().isEmpty() ? fallback : value.toString().trim();
    }

    /** 白名单键（AI 配置并入本端点）。 */
    private static boolean isAcceptedKey(String key) {
        return "mirror".equals(key) || "timeoutMs".equals(key) || "maxRetry".equals(key)
                || "commandTimeoutMs".equals(key) || "logLevel".equals(key) || "aiEnabled".equals(key)
                || "aiProvider".equals(key) || "aiBaseUrl".equals(key) || "aiApiKey".equals(key)
                || "aiModel".equals(key) || "download".equals(key);
    }

    /** 内置默认。 */
    private static Map<String, Object> defaults() {
        Map<String, Object> download = new LinkedHashMap<>();
        download.put("mirror", "https://repo.huaweicloud.com");
        download.put("timeoutMs", 60000);
        download.put("maxRetry", 3);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("download", download);
        settings.put("commandTimeoutMs", 600000);
        settings.put("logLevel", "INFO");
        settings.put("aiEnabled", false);
        settings.put("aiProvider", "deepseek");
        settings.put("aiBaseUrl", AI_TEMPLATES.get("deepseek").baseUrl());
        settings.put("aiModel", AI_TEMPLATES.get("deepseek").model());
        settings.put("aiApiKey", "");
        return settings;
    }

    /** AI 供应商模板：默认接入地址与推荐模型。 */
    public record AiTemplate(String baseUrl, String model) {
    }
}
