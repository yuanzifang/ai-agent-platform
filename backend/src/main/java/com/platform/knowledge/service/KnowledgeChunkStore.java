package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeParent;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeParentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分段/父块持久化：MySQL 批量写入失败时回退逐条写入，父块失败时保留
 * chunk.parent_content 兜底；事务只覆盖 MySQL，向量写入由调用方补偿清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeChunkStore {

    private final KnowledgeChunkMapper chunkMapper;

    @Autowired(required = false)
    private KnowledgeParentMapper parentMapper;

    @Autowired(required = false)
    @Qualifier("jdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public Map<Integer, Long> persist(KnowledgeDocument doc, List<KnowledgeChunk> chunks,
                                      List<String> warnings) {
        if (transactionManager == null) {
            return persistInTransaction(doc, chunks, warnings);
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> {
            Map<Integer, Long> ids = persistInTransaction(doc, chunks, warnings);
            if (ids.size() != chunks.size()) {
                throw new IllegalStateException("分段写入数量不匹配：expected=" + chunks.size()
                        + " actual=" + ids.size());
            }
            return ids;
        });
    }

    private Map<Integer, Long> persistInTransaction(KnowledgeDocument doc,
                                                    List<KnowledgeChunk> chunks,
                                                    List<String> warnings) {
        if (storeParents(doc, chunks, warnings)) {
            for (KnowledgeChunk chunk : chunks) chunk.setParentContent(null);
        }
        return insertChunks(doc, chunks);
    }

    /** 父块只存一份；失败时保留 chunk.parent_content 回退，不影响正文。 */
    private boolean storeParents(KnowledgeDocument doc, List<KnowledgeChunk> chunks,
                                 List<String> warnings) {
        if (parentMapper == null) return false;
        Map<Integer, KnowledgeChunk> parents = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            if (chunk.getParentIndex() == null || chunk.getParentContent() == null
                    || chunk.getParentContent().isBlank()) continue;
            parents.putIfAbsent(chunk.getParentIndex(), chunk);
        }
        if (parents.isEmpty()) return false;
        try {
            if (mysqlJdbcTemplate != null) {
                mysqlJdbcTemplate.update("DELETE FROM knowledge_parent WHERE document_id = ?", doc.getId());
                String sql = "INSERT INTO knowledge_parent " +
                        "(document_id, knowledge_base_id, parent_index, content, document_type, " +
                        "section_type, chunk_level, domain_keywords, relevance_score, " +
                        "regulatory_category, tenant_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                List<Object[]> batch = new ArrayList<>(parents.size());
                LocalDateTime now = LocalDateTime.now();
                for (var entry : parents.entrySet()) {
                    KnowledgeChunk chunk = entry.getValue();
                    batch.add(new Object[]{doc.getId(), doc.getKnowledgeBaseId(), entry.getKey(),
                            chunk.getParentContent(), chunk.getDocumentType(), chunk.getSectionType(),
                            chunk.getChunkLevel(), chunk.getDomainKeywords(), chunk.getRelevanceScore(),
                            chunk.getRegulatoryCategory(), doc.getTenantId(),
                            java.sql.Timestamp.valueOf(now)});
                }
                for (int start = 0; start < batch.size(); start += 500) {
                    mysqlJdbcTemplate.batchUpdate(sql,
                            batch.subList(start, Math.min(batch.size(), start + 500)));
                }
                return true;
            }
            parentMapper.delete(new LambdaQueryWrapper<KnowledgeParent>()
                    .eq(KnowledgeParent::getDocumentId, doc.getId()));
            for (var entry : parents.entrySet()) {
                KnowledgeChunk chunk = entry.getValue();
                KnowledgeParent parent = new KnowledgeParent();
                parent.setDocumentId(doc.getId());
                parent.setKnowledgeBaseId(doc.getKnowledgeBaseId());
                parent.setParentIndex(entry.getKey());
                parent.setContent(chunk.getParentContent());
                parent.setDocumentType(chunk.getDocumentType());
                parent.setSectionType(chunk.getSectionType());
                parent.setChunkLevel(chunk.getChunkLevel());
                parent.setDomainKeywords(chunk.getDomainKeywords());
                parent.setRelevanceScore(chunk.getRelevanceScore());
                parent.setRegulatoryCategory(chunk.getRegulatoryCategory());
                parent.setTenantId(doc.getTenantId());
                parent.setCreatedAt(LocalDateTime.now());
                parentMapper.insert(parent);
            }
            return true;
        } catch (Exception e) {
            log.warn("父块独立存储失败，回退 chunk.parent_content doc={} err={}",
                    doc.getId(), e.getMessage());
            warnings.add("父块独立存储失败，已回退重复存储：" + e.getMessage());
            return false;
        }
    }

    /** MySQL 分段批量写入；批量失败时清理并回退逐条插入，保证不产生半批数据。 */
    private Map<Integer, Long> insertChunks(KnowledgeDocument doc, List<KnowledgeChunk> chunks) {
        Map<Integer, Long> ids = new HashMap<>();
        if (mysqlJdbcTemplate != null && chunks.size() > 1) {
            try {
                String sql = """
                        INSERT INTO knowledge_chunk
                        (document_id, knowledge_base_id, chunk_index, parent_index, parent_content,
                         page_from, page_to, content, document_type, section_type, chunk_level,
                         parent_key, domain_keywords, relevance_score, regulatory_category,
                         allowed_users, tenant_visible, tenant_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                List<Object[]> batch = new ArrayList<>(chunks.size());
                for (KnowledgeChunk chunk : chunks) {
                    batch.add(new Object[]{chunk.getDocumentId(), chunk.getKnowledgeBaseId(),
                            chunk.getChunkIndex(), chunk.getParentIndex(), chunk.getParentContent(),
                            chunk.getPageFrom(), chunk.getPageTo(), chunk.getContent(),
                            chunk.getDocumentType(), chunk.getSectionType(), chunk.getChunkLevel(),
                            chunk.getParentKey(), chunk.getDomainKeywords(), chunk.getRelevanceScore(),
                            chunk.getRegulatoryCategory(),
                            chunk.getAllowedUsers(), chunk.getTenantVisible(), chunk.getTenantId(),
                            java.sql.Timestamp.valueOf(chunk.getCreatedAt())});
                }
                for (int start = 0; start < batch.size(); start += 500) {
                    mysqlJdbcTemplate.batchUpdate(sql,
                            batch.subList(start, Math.min(batch.size(), start + 500)));
                }
                for (Map<String, Object> row : mysqlJdbcTemplate.queryForList(
                        "SELECT id, chunk_index FROM knowledge_chunk WHERE document_id = ?",
                        doc.getId())) {
                    Object index = row.get("chunk_index");
                    Object id = row.get("id");
                    if (index instanceof Number i && id instanceof Number value) {
                        ids.put(i.intValue(), value.longValue());
                    }
                }
                if (ids.size() == chunks.size()) return ids;
                log.warn("MySQL 批量插入后分段数不匹配，回退逐条写入 doc={} expected={} actual={}",
                        doc.getId(), chunks.size(), ids.size());
                mysqlJdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", doc.getId());
                ids.clear();
            } catch (Exception e) {
                log.warn("MySQL 批量插入失败，回退逐条写入 doc={} err={}", doc.getId(), e.getMessage());
                try {
                    mysqlJdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", doc.getId());
                } catch (Exception ignored) {
                    // 清理失败时由调用方 cleanupPartial 兜底
                }
                ids.clear();
            }
        }
        for (KnowledgeChunk chunk : chunks) {
            chunkMapper.insert(chunk);
            ids.put(chunk.getChunkIndex(), chunk.getId());
        }
        return ids;
    }
}
