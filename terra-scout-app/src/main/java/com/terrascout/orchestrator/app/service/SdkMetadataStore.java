package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.catalog.CatalogUrlPolicy;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.CveSeverityEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SDK 元数据存储（外部文件 + sdk_version 表之间的加载/落盘/写回中枢）。
 *
 * <p>职责：
 * <ul>
 *   <li>首次启动把 classpath 内置 curated seed 落盘为外部文件（{@code config/sdk-metadata.json}）；</li>
 *   <li>按 sdk-metadata-schema.json 解析外部文件，逐条强校验（枚举 / https+域名白名单 /
 *       sha256 / 必填字段），非法条目跳过；</li>
 *   <li>以 (language, version, os, arch) 唯一键全量 upsert 进 {@code sdk_version}（只增改不删）；</li>
 *   <li>刷新成功后把库内元数据反向序列化写回外部文件（热重载可见、可审计）。</li>
 * </ul>
 */
@Component
public class SdkMetadataStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SdkMetadataStore.class);

    /** classpath 内置 curated seed 资源名。 */
    static final String SEED_RESOURCE = "sdk-metadata-seed.json";

    /** 外部文件 schema 版本。 */
    static final String SCHEMA_VERSION = "1.0";

    /** sha256 十六进制格式（小写 64 位）。 */
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

    private final SdkVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    public SdkMetadataStore(SdkVersionRepository versionRepository, ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 首次启动把内置 seed 落盘到 {data-root}/config/sdk-metadata.json；已存在则不动，
     * 保证外部文件是用户后续手动/官方刷新结果的唯一载体。
     *
     * @param dataRoot 数据根目录
     * @throws IOException seed 资源缺失或落盘失败
     */
    public void extractSeedIfAbsent(Path dataRoot) throws IOException {
        Path target = PathConstants.sdkMetadata(dataRoot);
        if (Files.isRegularFile(target)) {
            return;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IOException("classpath 资源不存在: " + SEED_RESOURCE);
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 解析外部元数据文件（schema 格式），逐条强校验，非法条目跳过并告警。
     * 文件不存在时返回空列表（由调用方决定语义）。
     *
     * @param file 外部文件路径
     * @return 校验通过的条目列表
     * @throws IOException 读取或 JSON 解析失败
     */
    public List<SdkCatalogEntry> loadFromFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(file.toFile());
        JsonNode sdks = root.path("sdks");
        if (!sdks.isArray()) {
            throw new IOException("SDK 元数据文件缺少 sdks 数组: " + file);
        }
        List<SdkCatalogEntry> entries = new ArrayList<>();
        int skipped = 0;
        for (JsonNode node : sdks) {
            SdkCatalogEntry entry = parseEntry(node);
            if (entry == null) {
                skipped++;
            } else {
                entries.add(entry);
            }
        }
        if (skipped > 0) {
            LOGGER.warn("SDK 元数据文件 {} 条非法条目被跳过: {}", skipped, file);
        }
        return entries;
    }

    /**
     * 以 (language, version, os, arch) 唯一键全量 upsert；已存在则更新除 id/createdAt
     * 外全部字段（releaseTime 仅在来条目非空时更新，防止日期源降级回退覆盖），
     * 不存在则新插入。只增改不删（刷新语义：过期版本保留由匹配算法过滤）。
     *
     * @param entries 待 upsert 条目
     * @return 实际处理（插入或更新）条数
     */
    @Transactional
    public int upsertAll(List<SdkCatalogEntry> entries) {
        int count = 0;
        for (SdkCatalogEntry entry : entries) {
            count += versionRepository.findByLanguageAndVersionAndOsAndArch(
                            entry.getLanguage(), entry.getVersion(), entry.getOs(), entry.getArch())
                    .map(existing -> {
                        apply(existing, entry);
                        versionRepository.save(existing);
                        return 1;
                    })
                    .orElseGet(() -> {
                        SdkVersion created = new SdkVersion();
                        apply(created, entry);
                        created.setId(UUID.randomUUID().toString());
                        created.setCreatedAt(System.currentTimeMillis());
                        versionRepository.save(created);
                        return 1;
                    });
        }
        return count;
    }

    /** 把库内全部元数据反向序列化为 schema 格式写回外部文件。 */
    public void writeExternalFile(Path file) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("updatedAt", Instant.now().toString());
        root.put("source", "official-refresh");
        ArrayNode sdks = root.putArray("sdks");
        for (SdkVersion entity : versionRepository.findAll()) {
            sdks.add(toJson(entity));
        }
        Files.createDirectories(file.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
    }

    /** 解析单条为目录条目；任一关键字段非法返回 null（计数跳过）。 */
    private SdkCatalogEntry parseEntry(JsonNode node) {
        SdkCatalogEntry entry = new SdkCatalogEntry();
        try {
            entry.setLanguage(LanguageEnum.valueOf(node.path("language").asText("")));
            entry.setOs(OsTypeEnum.valueOf(node.path("os").asText("")));
            entry.setArch(ArchEnum.valueOf(node.path("arch").asText("")));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String version = node.path("version").asText("");
        String url = node.path("url").asText("");
        String sha256 = node.path("sha256").asText("").toLowerCase(java.util.Locale.ROOT);
        if (version.isEmpty() || url.isEmpty()
                || !node.hasNonNull("lts") || !node.hasNonNull("eol")) {
            return null;
        }
        if (!CatalogUrlPolicy.isAllowed(url) || !SHA256_PATTERN.matcher(sha256).matches()) {
            return null;
        }
        entry.setVersion(version);
        entry.setDownloadUrl(url);
        entry.setSha256(sha256);
        entry.setSizeBytes(node.path("sizeBytes").isNumber() ? node.path("sizeBytes").asLong() : null);
        entry.setLts(node.path("lts").asBoolean(false));
        entry.setEol(node.path("eol").asBoolean(false));
        entry.setEolDate(parseDate(node.path("eolDate").asText("")));
        entry.setCveCount(node.path("cveCount").isInt() ? node.path("cveCount").asInt() : 0);
        entry.setHighestCveSeverity(parseSeverity(node.path("highestCveSeverity").asText("")));
        entry.setLicense(node.path("license").asText(null));
        entry.setDistribution(node.path("distribution").asText(null));
        entry.setVendor(node.path("vendor").asText(null));
        LocalDate releaseDate = parseDate(node.path("releaseDate").asText(""));
        entry.setReleaseTime(releaseDate == null ? null
                : releaseDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        return entry;
    }

    /** 条目字段写进实体（不含 id / createdAt，由调用方按插入/更新分支处置）。 */
    private static void apply(SdkVersion entity, SdkCatalogEntry entry) {
        entity.setLanguage(entry.getLanguage());
        entity.setVersion(entry.getVersion());
        entity.setOs(entry.getOs());
        entity.setArch(entry.getArch());
        entity.setDownloadUrl(entry.getDownloadUrl());
        entity.setSha256(entry.getSha256());
        entity.setSizeBytes(entry.getSizeBytes());
        entity.setLts(entry.isLts());
        entity.setEol(entry.isEol());
        entity.setEolDate(entry.getEolDate());
        entity.setCveCount(entry.getCveCount());
        entity.setHighestCveSeverity(entry.getHighestCveSeverity());
        entity.setLicense(entry.getLicense());
        entity.setDistribution(entry.getDistribution());
        entity.setVendor(entry.getVendor());
        // 日期源瞬时故障时抓取侧会降级为无日期：不允许 null 回退覆盖已入库日期
        if (entry.getReleaseTime() != null) {
            entity.setReleaseTime(entry.getReleaseTime());
        }
    }

    /** 实体 → schema JSON（downloadUrl → url，releaseTime → releaseDate）。 */
    private static ObjectNode toJson(SdkVersion entity) {
        ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("language", entity.getLanguage().name());
        node.put("version", entity.getVersion());
        node.put("os", entity.getOs().name());
        node.put("arch", entity.getArch().name());
        node.put("url", entity.getDownloadUrl());
        node.put("sha256", entity.getSha256());
        if (entity.getSizeBytes() != null) {
            node.put("sizeBytes", entity.getSizeBytes());
        }
        node.put("lts", entity.isLts());
        node.put("eol", entity.isEol());
        if (entity.getEolDate() != null) {
            node.put("eolDate", entity.getEolDate().toString());
        }
        node.put("cveCount", entity.getCveCount());
        node.put("highestCveSeverity", entity.getHighestCveSeverity().name());
        if (entity.getLicense() != null) {
            node.put("license", entity.getLicense());
        }
        if (entity.getDistribution() != null) {
            node.put("distribution", entity.getDistribution());
        }
        if (entity.getVendor() != null) {
            node.put("vendor", entity.getVendor());
        }
        if (entity.getReleaseTime() != null) {
            node.put("releaseDate", LocalDate.from(
                    Instant.ofEpochMilli(entity.getReleaseTime()).atZone(ZoneOffset.UTC)).toString());
        }
        return node;
    }

    /** 日期字符串解析（yyyy-MM-dd）；缺失/非法返回 null。 */
    private static LocalDate parseDate(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** CVE 级别解析（schema 枚举）；缺失/非法回退 NONE（实体列 NOT NULL 必须非空）。 */
    private static CveSeverityEnum parseSeverity(String text) {
        try {
            return text == null || text.isEmpty() ? CveSeverityEnum.NONE : CveSeverityEnum.valueOf(text);
        } catch (IllegalArgumentException e) {
            return CveSeverityEnum.NONE;
        }
    }
}
