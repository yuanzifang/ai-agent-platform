package com.platform.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.common.context.TenantContext;
import com.platform.system.entity.AuditLogin;
import com.platform.system.entity.AuditOperation;
import com.platform.system.mapper.AuditChainCheckpointMapper;
import com.platform.system.mapper.AuditLoginMapper;
import com.platform.system.mapper.SysTenantMapper;
import com.platform.system.entity.AuditChainCheckpoint;
import com.platform.system.entity.SysTenant;
import com.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 操作审计（需求 3.17/架构 8.4）：append-only + 哈希链（prev_hash = H(前条 hash ∥ 本条内容)）。
 * 关键操作在事务外记录，失败不阻断业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final com.platform.system.mapper.AuditOperationMapper auditOperationMapper;
    private final AuditChainCheckpointMapper auditCheckpointMapper;
    private final AuditLoginMapper auditLoginMapper;
    private final SysTenantMapper tenantMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager txManager;

    /**
     * 租户行锁（SELECT ... FOR UPDATE）保证多实例串行写链；锁随外层业务事务提交/回滚释放。
     * 没有外层事务时使用独立事务，确保锁和插入在同一事务内。
     */
    public void record(Long userId, String opType, String objectType, String objectId,
                       String result, Map<String, Object> detail) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return;
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                insertAudit(tenantId, userId, opType, objectType, objectId, result, detail);
            } else {
                TransactionTemplate tx = new TransactionTemplate(txManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(status -> {
                    try {
                        insertAudit(tenantId, userId, opType, objectType, objectId, result, detail);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("操作审计写入失败 op={}: {}", opType, e.getMessage());
        }
    }

    private void insertAudit(Long tenantId, Long userId, String opType, String objectType,
                             String objectId, String result, Map<String, Object> detail) throws Exception {
        // 先锁租户行，再读链尾（FOR UPDATE 读取最新已提交版本，避免 REPEATABLE READ 快照过期）
        auditOperationMapper.lockTenant(tenantId);
        AuditOperation last = auditOperationMapper.lockLast(tenantId);
        String prevHash;
        if (last != null) {
            prevHash = last.getPrevHash();
        } else {
            // 旧分区被归档/裁剪后，不能从 GENESIS 重新起链；从最新有效锚点继续
            List<AuditChainCheckpoint> checkpoints = auditCheckpointMapper.listByTenant(tenantId);
            prevHash = checkpoints.isEmpty() ? "GENESIS" : checkpoints.get(0).getLastRemovedHash();
        }
        LocalDateTime created = LocalDateTime.now().withNano(0);

        AuditOperation op = new AuditOperation();
        op.setTenantId(tenantId);
        op.setUserId(userId);
        op.setOpType(opType);
        op.setObjectType(objectType);
        op.setObjectId(objectId);
        op.setOpResult(result);
        op.setIp(currentIp());
        op.setHashVersion(2);
        op.setDetail(detail == null ? null : objectMapper.writeValueAsString(detail));
        op.setPrevHash(sha256(prevHash + "|" + opType + "|" + objectType + "|"
                + objectId + "|" + result + "|" + created));
        op.setCreatedAt(created);
        auditOperationMapper.insert(op);
    }

    public Map<String, Object> operations(long page, long pageSize, String opType) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BusinessException(403, "缺少租户上下文");
        Page<AuditOperation> result = auditOperationMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<AuditOperation>()
                        .eq(AuditOperation::getTenantId, tenantId)
                        .eq(opType != null && !opType.isBlank(), AuditOperation::getOpType, opType)
                        .orderByDesc(AuditOperation::getId));
        return Map.of("list", result.getRecords(), "total", result.getTotal(),
                "page", page, "pageSize", pageSize);
    }

    /** 登录日志：audit_login 豁免租户插件，这里显式按租户过滤（平台管理员登录 tenant 为空，不展示）。 */
    public Map<String, Object> logins(long page, long pageSize) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BusinessException(403, "缺少租户上下文");
        Page<AuditLogin> result = auditLoginMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<AuditLogin>()
                        .eq(AuditLogin::getTenantId, tenantId)
                        .orderByDesc(AuditLogin::getId));
        return Map.of("list", result.getRecords(), "total", result.getTotal(),
                "page", page, "pageSize", pageSize);
    }

    /**
     * 校验本租户哈希链（需求 3.17）：仅严格校验 hash_version>=2 的行（V1 历史行因并发/时间戳无法重算，计入跳过）。
     * 返回首个断链位置，便于审计排查。
     */
    public Map<String, Object> verifyChain() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BusinessException(403, "缺少租户上下文");
        List<AuditOperation> rows = auditOperationMapper.selectList(new LambdaQueryWrapper<AuditOperation>()
                .eq(AuditOperation::getTenantId, tenantId)
                .orderByAsc(AuditOperation::getId));
        // 选择“对应分区已经不存在”的最新锚点；若 DROP 失败分区仍存在，则自动退回上一条锚点。
        Set<String> activePartitions = new HashSet<>(auditCheckpointMapper.activeAuditPartitions());
        String anchorPartition = null;
        String prev = "GENESIS";
        for (AuditChainCheckpoint cp : auditCheckpointMapper.listByTenant(tenantId)) {
            if (!activePartitions.contains(cp.getPartitionName())) {
                prev = cp.getLastRemovedHash();
                anchorPartition = cp.getPartitionName();
                break;
            }
        }
        long checked = 0;
        long skippedLegacy = 0;
        Long brokenAtId = null;
        String brokenAtTime = null;
        for (AuditOperation op : rows) {
            boolean v2 = op.getHashVersion() != null && op.getHashVersion() >= 2;
            if (v2) {
                LocalDateTime ts = op.getCreatedAt() == null ? null : op.getCreatedAt().withNano(0);
                String expected = sha256(prev + "|" + op.getOpType() + "|" + op.getObjectType() + "|"
                        + op.getObjectId() + "|" + op.getOpResult() + "|" + ts);
                checked++;
                if (!expected.equals(op.getPrevHash())) {
                    brokenAtId = op.getId();
                    brokenAtTime = ts == null ? null : ts.toString();
                    break;
                }
            } else {
                skippedLegacy++;
            }
            prev = op.getPrevHash();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", brokenAtId == null);
        result.put("total", rows.size());
        result.put("checked", checked);
        result.put("skippedLegacy", skippedLegacy);
        result.put("anchorPartition", anchorPartition);
        result.put("brokenAtId", brokenAtId);
        result.put("brokenAtTime", brokenAtTime);
        result.put("message", brokenAtId == null
                ? "哈希链校验通过（V2 行 " + checked + " 条；历史 V1 行 " + skippedLegacy + " 条未校验）"
                : "哈希链在记录 #" + brokenAtId + " 处断裂，请排查数据篡改或并发写入");
        return result;
    }

    private String currentIp() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String xff = attrs.getRequest().getHeader("X-Forwarded-For");
                return (xff != null && !xff.isBlank() ? xff.split(",")[0].trim()
                        : attrs.getRequest().getRemoteAddr());
            }
        } catch (Exception e) {
            log.warn("审计请求上下文读取失败：{}", e.getMessage());
        }
        return null;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "HASH-ERROR";
        }
    }
}
