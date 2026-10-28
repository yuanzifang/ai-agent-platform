package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.common.context.TenantContext;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeVectorOutbox;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.knowledge.mapper.KnowledgeVectorOutboxMapper;
import com.platform.system.entity.SysTenant;
import com.platform.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MySQL/PgVector outbox 恢复器：解析任务在开始时持久化，正常成功标记 DONE。
 * 进程崩溃后由本任务在租约到期后重跑解析；超过重试次数则清理并失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeVectorOutboxScheduler {

    private final SysTenantMapper tenantMapper;
    private final KnowledgeVectorOutboxMapper outboxMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeService knowledgeService;
    private final VectorStore vectorRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String WORKER_ID = workerId();

    @Value("${knowledge.outbox.batch:10}")
    private int batch = 10;
    @Value("${knowledge.outbox.stale-minutes:30}")
    private int staleMinutes = 30;
    @Value("${knowledge.outbox.lease-minutes:5}")
    private int leaseMinutes = 5;

    @Scheduled(fixedDelayString = "${knowledge.outbox.interval-ms:60000}",
            initialDelayString = "${knowledge.outbox.initial-delay-ms:60000}")
    public void run() {
        List<SysTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .in(SysTenant::getStatus, List.of("ACTIVE", "TRIAL")));
        if (tenants == null || tenants.isEmpty()) return;
        for (SysTenant tenant : tenants) {
            try {
                final Long tenantId = tenant.getId();
                TenantContext.runWith(tenantId, this::processTenant);
            } catch (Exception e) {
                log.warn("outbox 恢复失败 tenant={} err={}", tenant.getId(), e.getMessage());
            }
        }
    }

    /** 当前租户下扫描并处理一批 outbox。返回恢复的文档数。 */
    public int processTenant() {
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeVectorOutbox> tasks = outboxMapper.selectList(
                new LambdaQueryWrapper<KnowledgeVectorOutbox>()
                        .eq(KnowledgeVectorOutbox::getStatus, "PENDING")
                        .le(KnowledgeVectorOutbox::getAvailableAt, now)
                        .apply("(locked_until IS NULL OR locked_until < NOW())")
                        .orderByAsc(KnowledgeVectorOutbox::getAvailableAt)
                        .last("LIMIT " + Math.max(1, Math.min(batch, 50))));
        int recovered = 0;
        int claimedCount = 0;
        if (tasks == null) return 0;
        for (KnowledgeVectorOutbox task : tasks) {
            int claimed = outboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                    .eq(KnowledgeVectorOutbox::getId, task.getId())
                    .eq(KnowledgeVectorOutbox::getStatus, "PENDING")
                    .le(KnowledgeVectorOutbox::getAvailableAt, now)
                    .apply("(locked_until IS NULL OR locked_until < NOW())")
                    .set(KnowledgeVectorOutbox::getAvailableAt,
                            now.plusMinutes(Math.max(1, leaseMinutes)))
                    .set(KnowledgeVectorOutbox::getLockedBy, WORKER_ID)
                    .set(KnowledgeVectorOutbox::getLockedUntil,
                            now.plusMinutes(Math.max(1, leaseMinutes)))
                    .set(KnowledgeVectorOutbox::getUpdatedAt, now));
            if (claimed == 0) continue;
            claimedCount++;
            if (processTask(task)) recovered++;
        }
        if (claimedCount > 0) {
            log.info("vector outbox 认领 {} 条 worker={}", claimedCount, WORKER_ID);
        }
        return recovered;
    }

    private boolean processTask(KnowledgeVectorOutbox task) {
        KnowledgeDocument doc = docMapper.selectById(task.getDocumentId());
        if (doc == null) {
            markFailed(task, "文档不存在");
            return false;
        }
        if ("DONE".equals(doc.getStatus())) {
            markDone(task);
            return false;
        }
        if ("FAILED".equals(doc.getStatus())) {
            markFailed(task, doc.getErrorMessage());
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime started = doc.getParseStartedAt();
        if (started != null && started.isAfter(now.minusMinutes(Math.max(1, staleMinutes)))) {
            // 仍在正常解析窗口内，租约到期后再看。
            return false;
        }
        if (task.getPayload() != null && !task.getPayload().isBlank()
                && task.getDimensions() != null && task.getDimensions() > 0) {
            if (applyPayload(task, doc)) return true;
            // 补写失败时回退到重跑解析；cleanupPartial 会清空 payload。
        }
        int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
        if (attempts >= 3) {
            knowledgeService.failInterruptedForOutbox(doc.getId(),
                    "MySQL/PgVector 同步超过 3 次未完成，已清理残留；请重新解析");
            markFailed(task, "outbox 恢复超过 3 次");
            return false;
        }
        outboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                .eq(KnowledgeVectorOutbox::getId, task.getId())
                .set(KnowledgeVectorOutbox::getAttempts, attempts + 1)
                .set(KnowledgeVectorOutbox::getAvailableAt, now.plusMinutes(Math.max(1, leaseMinutes)))
                .set(KnowledgeVectorOutbox::getUpdatedAt, now));
        if (!knowledgeService.retryInterruptedForOutbox(doc.getId())) {
            markFailed(task, "文档状态已变化");
            return false;
        }
        log.warn("outbox 恢复解析：doc={} attempt={}", doc.getId(), attempts + 1);
        return true;
    }

    /** 直接用 outbox 中的内容/向量补写 PgVector，避免重新解析和重新嵌入。 */
    private boolean applyPayload(KnowledgeVectorOutbox task, KnowledgeDocument doc) {
        try {
            List<Map<String, Object>> payload = objectMapper.readValue(task.getPayload(),
                    new TypeReference<List<Map<String, Object>>>() { });
            if (payload.isEmpty()) return false;
            List<Map<String, Object>> rows = new ArrayList<>(payload.size());
            for (Map<String, Object> raw : payload) {
                Map<String, Object> row = new java.util.LinkedHashMap<>(raw);
                Object embedding = row.get("embedding");
                if (!(embedding instanceof List<?> values) || values.isEmpty()) return false;
                float[] vector = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    vector[i] = ((Number) values.get(i)).floatValue();
                }
                if (vector.length != task.getDimensions()) return false;
                row.put("embedding", vector);
                row.put("effective_from", timestamp(row.get("effective_from")));
                row.put("effective_to", timestamp(row.get("effective_to")));
                rows.add(row);
            }
            vectorRepository.deleteByDocument(doc.getTenantId(), doc.getId());
            vectorRepository.saveBatch(doc.getTenantId(), rows, task.getDimensions());
            long actual = vectorRepository.countByDocument(doc.getTenantId(), doc.getId(),
                    task.getDimensions());
            if (actual != rows.size()) {
                vectorRepository.deleteByDocument(doc.getTenantId(), doc.getId());
                return false;
            }
            if (!knowledgeService.completeOutboxVectors(doc.getId(), rows.size())) return false;
            markDone(task);
            log.warn("outbox 直接补写向量完成：doc={} rows={}", doc.getId(), rows.size());
            return true;
        } catch (Exception e) {
            log.warn("outbox 向量补写失败，回退重跑解析：doc={} err={}", doc.getId(), e.getMessage());
            try { vectorRepository.deleteByDocument(doc.getTenantId(), doc.getId()); }
            catch (Exception ignored) { }
            return false;
        }
    }

    private Timestamp timestamp(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return new Timestamp(number.longValue());
        String text = String.valueOf(value);
        try {
            return Timestamp.valueOf(text.replace('T', ' ').replace("Z", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private void markDone(KnowledgeVectorOutbox task) {
        outboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                .eq(KnowledgeVectorOutbox::getId, task.getId())
                .eq(KnowledgeVectorOutbox::getLockedBy, WORKER_ID)
                .set(KnowledgeVectorOutbox::getStatus, "DONE")
                .set(KnowledgeVectorOutbox::getLockedBy, null)
                .set(KnowledgeVectorOutbox::getLockedUntil, null)
                .set(KnowledgeVectorOutbox::getLastError, null)
                .set(KnowledgeVectorOutbox::getUpdatedAt, LocalDateTime.now()));
    }

    private static String workerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "vector-outbox-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private void markFailed(KnowledgeVectorOutbox task, String error) {
        String message = error == null ? null : error.substring(0, Math.min(1000, error.length()));
        outboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                .eq(KnowledgeVectorOutbox::getId, task.getId())
                .eq(KnowledgeVectorOutbox::getLockedBy, WORKER_ID)
                .set(KnowledgeVectorOutbox::getStatus, "FAILED")
                .set(KnowledgeVectorOutbox::getLockedBy, null)
                .set(KnowledgeVectorOutbox::getLockedUntil, null)
                .set(KnowledgeVectorOutbox::getLastError, message)
                .set(KnowledgeVectorOutbox::getUpdatedAt, LocalDateTime.now()));
    }
}
