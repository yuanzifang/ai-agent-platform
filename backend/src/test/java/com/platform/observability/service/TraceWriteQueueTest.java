package com.platform.observability.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 2：Trace 写队列的排空与读后写一致性。 */
class TraceWriteQueueTest {

    @Test
    void flushWaitsUntilQueuedTaskFinishes() {
        TraceWriteQueue queue = new TraceWriteQueue(10, 10, 10);
        queue.start();
        AtomicInteger completed = new AtomicInteger();
        try {
            queue.submit(false, () -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completed.incrementAndGet();
            });
            queue.flush(1000);
            assertEquals(1, completed.get());
        } finally {
            queue.stop();
        }
    }

    @Test
    void fullNormalQueueDropsWithoutBlockingCaller() {
        TraceWriteQueue queue = new TraceWriteQueue(1, 1, 1);
        // 不启动 worker，模拟写入线程繁忙/卡住。
        long start = System.nanoTime();
        queue.submit(false, () -> { });
        queue.submit(false, () -> { });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 500, "队列满时不应阻塞业务线程");
        assertTrue(queue.droppedCount() >= 1);
        queue.stop();
    }
}
