package com.terrascout.orchestrator.app.unit;

import java.util.ArrayList;
import java.util.List;

import com.terrascout.orchestrator.app.repository.AuditLogRepository;
import com.terrascout.orchestrator.app.service.AuditService;
import com.terrascout.orchestrator.core.domain.AuditLog;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计 hash 链单测：链式接续 + 篡改检测。
 */
class AuditServiceTest {

    private final AuditLogRepository repo = mock(AuditLogRepository.class);
    private final AuditService service = new AuditService(repo);

    @Test
    void recordBuildsNonNullHashesInChain() {
        // 首个记录 prevHash=null
        when(repo.findAll(Sort.by(Sort.Direction.DESC, "seq")))
                .thenReturn(new ArrayList<>());
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLog first = service.record("b1", "PROJECT_ANALYZE", "project", "p1", null, "{}", "SUCCESS");
        assertThat(first.getPrevHash()).isNull();
        assertThat(first.getHash()).hasSize(64);

        // 后续记录 prevHash=first.hash
        when(repo.findAll(Sort.by(Sort.Direction.DESC, "seq"))).thenReturn(List.of(first));
        AuditLog second = service.record("b2", "TASK_CREATE", "task", "t1", null, "{}", "SUCCESS");
        assertThat(second.getPrevHash()).isEqualTo(first.getHash());
        assertThat(second.getHash()).hasSize(64).isNotEqualTo(first.getHash());
    }

    @Test
    void chainValidAcceptsIntactChain() {
        List<AuditLog> chain = buildChain(3);
        when(repo.findAll(any(Sort.class))).thenReturn(chain);
        assertThat(service.chainValid(chain)).isTrue();
    }

    @Test
    void chainValidDetectsTamperedHash() {
        List<AuditLog> chain = buildChain(3);
        chain.get(1).setHash("0000000000000000000000000000000000000000000000000000000000000000");
        when(repo.findAll(any(Sort.class))).thenReturn(chain);
        assertThat(service.chainValid(chain)).isFalse();
    }

    @Test
    void chainValidDetectsChangedAction() {
        List<AuditLog> chain = buildChain(2);
        chain.get(1).setAction("TAMPERED");
        when(repo.findAll(any(Sort.class))).thenReturn(chain);
        assertThat(service.chainValid(chain)).isFalse();
    }

    /** 构造一条自洽的 hash 链（用 service 自身生成，保证链内 hash 正确接续）。 */
    private List<AuditLog> buildChain(int count) {
        List<AuditLog> result = new ArrayList<>();
        // repo.findAll(SORT_DESC) 在 build 时返回当前已生成内容（模拟 DB 倒序：最新在前）
        when(repo.findAll(Sort.by(Sort.Direction.DESC, "seq")))
                .thenAnswer(inv -> {
                    List<AuditLog> desc = new ArrayList<>(result);
                    java.util.Collections.reverse(desc);
                    return desc;
                });
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog saved = inv.getArgument(0);
            result.add(saved);
            return saved;
        });
        for (int i = 0; i < count; i++) {
            service.record("b" + i, "ACT_" + i, "task", "t" + i, null, "{}", "SUCCESS");
        }
        return result;
    }
}
