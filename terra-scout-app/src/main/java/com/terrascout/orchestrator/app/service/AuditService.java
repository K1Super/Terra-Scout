package com.terrascout.orchestrator.app.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import com.terrascout.orchestrator.app.repository.AuditLogRepository;
import com.terrascout.orchestrator.core.domain.AuditLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计服务：hash 链（security.md 6.8 / AuditLog 注释），防无意篡改。
 *
 * <p>规则：{@code hash = SHA-256(prev_hash + biz_id + action + target_type + target_id
 * + before_json + after_json + result + created_at)}；首条 prev_hash = null。
 * {@code chainValid(list)} 重放整链校验，供 {@code GET /api/v1/audit} 返回。
 */
@Service
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** 追加一条审计记录，自动接续当前链尾。 */
    @Transactional
    public AuditLog record(String bizId, String action, String targetType, String targetId,
                           String beforeJson, String afterJson, String result) {
        AuditLog entry = new AuditLog();
        entry.setBizId(bizId);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setBeforeJson(beforeJson);
        entry.setAfterJson(afterJson);
        entry.setResult(result);
        entry.setCreatedAt(System.currentTimeMillis());

        String prevHash = lastHash();
        entry.setPrevHash(prevHash);
        entry.setHash(computeHash(prevHash, entry));
        return repository.save(entry);
    }

    /** 返回链尾记录 hash；无记录返回 null（首条 prev_hash）。 */
    @Transactional(readOnly = true)
    public String lastHash() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "seq"))
                .stream().findFirst().map(AuditLog::getHash).orElse(null);
    }

    /** 全部审计记录（seq 升序），供查询与校验。 */
    @Transactional(readOnly = true)
    public List<AuditLog> findAllOrderedBySeq() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, "seq"));
    }

    /**
     * 重放 hash 链校验当前集合是否完整且未被改（security.md 6.8：防无意篡改）。
     * 校验失败时返回为 false；不会修改数据库。
     */
    @Transactional(readOnly = true)
    public boolean chainValid(List<AuditLog> entries) {
        String previous = null;
        for (AuditLog entry : entries) {
            String expected = computeHash(previous, entry);
            if (!expected.equals(entry.getHash())) {
                LOG.warn("审计 hash 链断裂: seq={}", entry.getSeq());
                return false;
            }
            previous = entry.getHash();
        }
        return true;
    }

    /** 计算单条记录 hash。 */
    private static String computeHash(String prevHash, AuditLog entry) {
        String content = nz(prevHash) + nz(entry.getBizId()) + nz(entry.getAction())
                + nz(entry.getTargetType()) + nz(entry.getTargetId())
                + nz(entry.getBeforeJson()) + nz(entry.getAfterJson())
                + nz(entry.getResult()) + entry.getCreatedAt();
        return sha256(content);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** SHA-256 十六进制（复用 security.md 6.8 口径）。 */
    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
