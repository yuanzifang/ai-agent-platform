package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单分段 ACL 更新：先更新向量 metadata，再写 MySQL；MySQL 失败时补偿旧向量 ACL。
 * 跨存储无法强事务，但至少避免“接口报错、MySQL 已改、向量仍是旧 ACL”的常见不一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkAclService {

    private final KnowledgeChunkMapper chunkMapper;
    private final VectorStore vectorRepository;
    private final KnowledgePermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> update(Long kbId, Long chunkId, List<Long> allowedUsers,
                                      boolean tenantVisible,
                                      KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(kbId, viewer);
        KnowledgeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || !Objects.equals(chunk.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "分段不存在");
        }
        if (chunk.getDocumentId() == null) throw new BusinessException(400, "分段缺少文档信息");
        List<Long> users = allowedUsers == null ? new ArrayList<>() : new ArrayList<>(allowedUsers);
        permissionService.validateAclUsers(kb.getTenantId(), users);

        List<Long> oldUsers = parseUsers(chunk.getAllowedUsers());
        boolean oldTenantVisible = chunk.getTenantVisible() == null || chunk.getTenantVisible() == 1;
        try {
            vectorRepository.updateAclForChunk(kb.getTenantId(), kbId, chunkId, users, tenantVisible);
            int updated = chunkMapper.update(null, new LambdaUpdateWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getId, chunkId)
                    .set(KnowledgeChunk::getAllowedUsers, usersJson(users))
                    .set(KnowledgeChunk::getTenantVisible, tenantVisible ? 1 : 0));
            if (updated == 0) throw new BusinessException(404, "分段不存在");
        } catch (Exception e) {
            // 向量可能已改成功；MySQL 失败时尽力恢复旧 ACL，避免权限收紧/放宽不一致。
            try {
                vectorRepository.updateAclForChunk(kb.getTenantId(), kbId, chunkId,
                        oldUsers, oldTenantVisible);
            } catch (Exception compensate) {
                log.warn("分段 ACL 向量补偿失败 kb={} chunk={} err={}",
                        kbId, chunkId, compensate.getMessage());
            }
            throw e;
        }
        auditService.record(viewer.userId(), "knowledge.chunk_acl", "knowledge_chunk",
                String.valueOf(chunkId), "SUCCESS",
                Map.of("knowledgeBaseId", kbId, "tenantVisible", tenantVisible,
                        "allowedUsers", users));
        return Map.of("id", chunkId, "allowedUsers", users, "tenantVisible", tenantVisible);
    }

    private String usersJson(List<Long> users) {
        return users.stream().map(String::valueOf).map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private List<Long> parseUsers(String raw) {
        Object parsed = KnowledgeJsonSupport.parse(objectMapper, raw);
        if (!(parsed instanceof List<?> list)) return List.of();
        List<Long> users = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof Number n) users.add(n.longValue());
            else {
                try {
                    users.add(Long.parseLong(String.valueOf(value)));
                } catch (NumberFormatException ignored) {
                    // 忽略损坏的 ACL 项，补偿时按可解析项恢复
                }
            }
        }
        return users;
    }
}
