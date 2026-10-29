package com.platform.common.ratelimit;

import com.platform.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * LLM 并发准入信号量（漏斗最后一块板）：限制同时在途的模型调用数，
 * 超出预算的请求快速失败（429 + 稍后重试语义），而不是全部堆到模型上游排队拖垮整体延迟。
 *
 * <p>max-llm=0（默认）时不限并发、零开销。wait-ms=0 时不排队直接 429；
 * wait-ms>0 允许短暂排队（适合突发毛刺）。进程级信号量：多副本部署时每副本独立预算
 * （副本数 × max-llm = 全局预算）。</p>
 */
@Component
public class ModelConcurrencyGuard {

    private final Semaphore semaphore;
    private final int waitMs;
    private final boolean enabled;

    public ModelConcurrencyGuard(
            @Value("${model.concurrency.max-llm:0}") int maxLlm,
            @Value("${model.concurrency.wait-ms:0}") long waitMs) {
        this.enabled = maxLlm > 0;
        this.semaphore = new Semaphore(Math.max(maxLlm, 1), true);
        this.waitMs = (int) Math.max(0, waitMs);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 阻塞获取一个模型调用名额；超预算时按 wait-ms 排队或直接抛 429。 */
    public void acquire() {
        if (!enabled) return;
        boolean acquired = false;
        try {
            acquired = waitMs > 0
                    ? semaphore.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
                    : semaphore.tryAcquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            throw new BusinessException(429, "模型并发已达上限，请稍后重试");
        }
    }

    public void release() {
        if (enabled) semaphore.release();
    }
}
