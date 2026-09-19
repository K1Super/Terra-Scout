package com.terrascout.orchestrator.app.unit;

import java.util.Map;

import com.terrascout.orchestrator.app.controller.ShutdownController;
import com.terrascout.orchestrator.app.repository.AuditLogRepository;
import com.terrascout.orchestrator.app.service.AuditService;
import com.terrascout.orchestrator.core.dto.ApiResponse;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 优雅关闭控制器单测：返回 SHUTTING_DOWN 并异步触发 {@code context.close()}。
 *
 * <p>不放在共享 @SpringBootTest 上下文（避免关闭共享 context 影响其他测试），用 mock context；
 * {@link ShutdownController} 在 daemon 线程 (sleep 200ms) 后关闭 Spring 上下文。
 */
class ShutdownControllerTest {

    @Test
    void shutdownReturnsStatusAndClosesAsync() throws Exception {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ShutdownController controller = new ShutdownController(context, new AuditService(repo));

        ApiResponse<Map<String, String>> response = controller.shutdown();
        assertThat(response.getCode()).isEqualTo(200000);
        assertThat(response.getData().get("status")).isEqualTo("SHUTTING_DOWN");

        // 轮询等待异步线程调用 context.close()（sleep 200ms + 余量）
        long deadline = System.currentTimeMillis() + 3000;
        boolean closed = false;
        while (System.currentTimeMillis() < deadline && !closed) {
            Thread.sleep(100);
            closed = closeInvoked(context);
        }
        assertThat(closed).isTrue();
    }

    private static boolean closeInvoked(ConfigurableApplicationContext context) {
        try {
            verify(context).close();
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }
}
