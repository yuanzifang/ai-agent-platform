package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.common.context.TenantContext;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.entity.SysTenant;
import com.platform.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 定时核对 DONE 文档的 chunk_count 与 PgVector 实际行数，发现跨库不一致。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeVectorAuditScheduler {

    private final SysTenantMapper tenantMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final VectorStore vectorRepository;

    @Value("${knowledge.vector-audit.batch:20}")
    private int batch = 20;
    @Value("${knowledge.vector-audit.recheck-days:7}")
    private int recheckDays = 7;

    @Scheduled(fixedDelayString = "${knowledge.vector-audit.interval-ms:3600000}",
            initialDelayString = "${knowledge.vector-audit.initial-delay-ms:120000}")
    public void run() {
        List<SysTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .in(SysTenant::getStatus, List.of("ACTIVE", "TRIAL")));
        if (tenants == null || tenants.isEmpty()) return;
        for (SysTenant tenant : tenants) {
            try {
                final Long tenantId = tenant.getId();
                TenantContext.runWith(tenantId, this::auditTenant);
            } catch (Exception e) {
                log.warn("向量一致性审计失败 tenant={} err={}", tenant.getId(), e.getMessage());
            }
        }
    }

    /** 当前租户单批审计；返回 {checked, mismatched}。 */
    public java.util.Map<String, Integer> auditTenant() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, recheckDays));
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getStatus, "DONE")
                        .isNotNull(KnowledgeDocument::getEmbeddingDimensions)
                        .and(w -> w.isNull(KnowledgeDocument::getVectorVerifiedAt)
                                .or().lt(KnowledgeDocument::getVectorVerifiedAt, cutoff))
                        .orderByAsc(KnowledgeDocument::getVectorVerifiedAt)
                        .last("LIMIT " + Math.max(1, Math.min(batch, 100))));
        int checked = 0;
        int mismatched = 0;
        if (docs == null) return java.util.Map.of("checked", 0, "mismatched", 0);
        for (KnowledgeDocument doc : docs) {
            checked++;
            try {
                int expected = doc.getChunkCount() == null ? 0 : doc.getChunkCount();
                long actual = vectorRepository.countByDocument(doc.getTenantId(), doc.getId(),
                        doc.getEmbeddingDimensions());
                if (actual != expected) {
                    mismatched++;
                    String warning = "向量一致性校验失败：expected=" + expected + " actual=" + actual
                            + "，建议重新解析文档";
                    docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getId, doc.getId())
                            .set(KnowledgeDocument::getVectorVerifiedAt, LocalDateTime.now())
                            .set(KnowledgeDocument::getParseWarning, warning));
                    log.warn("向量数量不一致 doc={} expected={} actual={}", doc.getId(), expected, actual);
                } else {
                    docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getId, doc.getId())
                            .set(KnowledgeDocument::getVectorVerifiedAt, LocalDateTime.now()));
                }
            } catch (Exception e) {
                log.warn("向量审计跳过 doc={} err={}", doc.getId(), e.getMessage());
            }
        }
        return java.util.Map.of("checked", checked, "mismatched", mismatched);
    }
}
