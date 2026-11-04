package com.platform.observability.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace 写入有界队列。
 *
 * <p>业务线程只负责入队；专用单线程按批排空并落库。队列满时优先保留
 * ERROR/BLOCKED，普通 Span 允许丢弃，避免可观测性反压拖慢对话。</p>
 */
@Slf4j
@Component
public class TraceWriteQueue {

    private final BlockingQueue<Runnable> normal;
    private final BlockingQueue<Runnable> priority;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private Thread worker;

    public TraceWriteQueue(
            @Value("${observability.trace.write-queue-capacity:2000}") int capacity,
            @Value("${observability.trace.priority-queue-capacity:500}") int priorityCapacity,
            @Value("${observability.trace.write-batch-size:100}") int batchSize) {
        this.normal = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.priority = new ArrayBlockingQueue<>(Math.max(1, priorityCapacity));
        this.batchSize = Math.max(1, batchSize);
    }

    private final int batchSize;

    @PostConstruct
    void start() {
        worker = new Thread(this::runLoop, "trace-write");
        worker.setDaemon(true);
        worker.start();
    }

    /** priority=true 用于 ERROR/BLOCKED；队列满时也不阻塞业务线程。 */
    public void submit(boolean priority, Runnable task) {
        if (task == null) return;
        BlockingQueue<Runnable> target = priority ? this.priority : normal;
        if (!target.offer(task)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.warn("Trace 写入队列已满，丢弃任务累计={} priority={}", n, priority);
            }
        }
    }

    private void runLoop() {
        while (running || !priority.isEmpty() || !normal.isEmpty()) {
            try {
                Runnable first = priority.poll();
                if (first == null) first = normal.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                first.run();
                // 合并同一批，减少线程唤醒；任务本身仍可安全独立执行。
                for (int i = 1; i < batchSize; i++) {
                    Runnable next = priority.poll();
                    if (next == null) next = normal.poll();
                    if (next == null) break;
                    next.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Trace 写入任务异常：{}", e.getMessage());
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    public long droppedCount() {
        return dropped.get();
    }
}
