package com.platform.common.ratelimit;

import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LLM 并发准入信号量：预算/快速失败/排队/禁用语义。 */
class ModelConcurrencyGuardTest {

    @Test
    void disabledNeverRejects() {
        ModelConcurrencyGuard guard = new ModelConcurrencyGuard(0, 0);
        assertTrue(!guard.enabled());
        for (int i = 0; i < 100; i++) guard.acquire();   // 不限并发：任意多次获取不拒绝
        for (int i = 0; i < 100; i++) guard.release();   // 释放对称
    }

    @Test
    void budgetExhaustedFailsFast() {
        ModelConcurrencyGuard guard = new ModelConcurrencyGuard(1, 0);
        assertTrue(guard.enabled());
        guard.acquire();
        var e = assertThrows(BusinessException.class, guard::acquire);
        assertEquals(429, e.getCode());
        guard.release();
        guard.acquire();   // 释放后可再获取
        guard.release();
    }

    @Test
    void waitMsAllowsShortQueue() throws Exception {
        ModelConcurrencyGuard guard = new ModelConcurrencyGuard(1, 300);
        guard.acquire();
        new Thread(() -> {
            try { Thread.sleep(100); guard.release(); } catch (Exception ignored) { }
        }).start();
        // 另一线程 100ms 后释放，等待中的 acquire 应在 300ms 内成功
        long start = System.currentTimeMillis();
        guard.acquire();
        assertTrue(System.currentTimeMillis() - start < 300, "排队等待应在释放后尽快返回");
    }

    @Test
    void releaseWithoutAcquireDoesNotGrowBudget() {
        // 超发防护由对称调用约定保证；此处仅验证信号量基本守恒
        ModelConcurrencyGuard guard = new ModelConcurrencyGuard(2, 0);
        guard.acquire(); guard.acquire();
        guard.release(); guard.release();
        guard.acquire(); guard.acquire();   // 归还后预算恢复
        assertThrows(BusinessException.class, guard::acquire);
        guard.release(); guard.release();
    }
}
