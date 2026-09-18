package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * 运行时设置服务（rest-schema.md 3.4.15 / master-plan D-013）。
 *
 * <p>持久化于 {@code {data-root}/config/user-settings.json}，只接受白名单键；
 * 优先级：内置默认 → 文件。文件损坏时回退默认并重建（不允许因坏配置起不来）。
 */
@Service
public class SettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    private final ObjectMapper objectMapper;

    public SettingsService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 读取运行时设置（内置默认打底，会话覆盖多余键）。 */
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

    /** 保存运行时设置（白名单键），原子写文件。 */
    public Map<String, Object> save(Map<String, Object> input) {
        Map<String, Object> accepted = defaults();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (isAcceptedKey(entry.getKey()) && entry.getValue() != null) {
                accepted.put(entry.getKey(), entry.getValue());
            }
        }
        Path file = PathConstants.userSettings(PathConstants.dataRoot());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "user-settings", ".json.tmp");
            Files.write(tmp, objectMapper.writeValueAsString(accepted).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("user-settings.json 写出失败", e);
        }
        return accepted;
    }

    /** 白名单键（rest-schema 3.4.15；AI 开关并入本端点）。 */
    private static boolean isAcceptedKey(String key) {
        return "mirror".equals(key) || "timeoutMs".equals(key) || "maxRetry".equals(key)
                || "commandTimeoutMs".equals(key) || "logLevel".equals(key) || "aiEnabled".equals(key)
                || "download".equals(key);
    }

    /** 内置默认（D-013 优先级底）。 */
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
        return settings;
    }
}
