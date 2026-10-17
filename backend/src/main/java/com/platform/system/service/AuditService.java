package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.common.context.TenantContext;
import com.platform.system.entity.AuditLogin;
import com.platform.system.entity.AuditOperation;
import com.platform.system.mapper.AuditLoginMapper;
import com.platform.system.mapper.SysTenantMapper;
import com.platform.system.entity.SysTenant;
import com.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作审计（需求 3.17/架构 8.4）：append-only + 哈希链（prev_hash = H(前条 hash ∥ 本条内容)）。
 * 关键操作在事务外记录，失败不阻断业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final com.platform.system.mapper.AuditOperationMapper auditOperationMapper;
    private final AuditLoginMapper auditLoginMapper;
    private final SysTenantMapper tenantMapper;

    public void record(Long userId, String opType, String objectType, String objectId,
                       String result, Map<String, Object> detail) {
        try {
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) return;
            AuditOperation last = auditOperationMapper.selectOne(
                    new LambdaQueryWrapper<AuditOperation>()
                            .eq(AuditOperation::getTenantId, tenantId)
                            .orderByDesc(AuditOperation::getId)
                            .last("LIMIT 1"));
            String prevHash = last == null ? "GENESIS" : last.getPrevHash();

            AuditOperation op = new AuditOperation();
            op.setTenantId(tenantId);
            op.setUserId(userId);
            op.setOpType(opType);
            op.setObjectType(objectType);
            op.setObjectId(objectId);
            op.setOpResult(result);
            op.setIp(currentIp());
            op.setPrevHash(sha256(prevHash + "|" + opType + "|" + objectType + "|"
                    + objectId + "|" + result + "|" + LocalDateTime.now().withNano(0)));
            op.setCreatedAt(LocalDateTime.now());
            auditOperationMapper.insert(op);
        } catch (Exception e) {
            log.warn("操作审计写入失败 op={}: {}", opType, e.getMessage());
        }
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

    private String currentIp() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String xff = attrs.getRequest().getHeader("X-Forwarded-For");
                return (xff != null && !xff.isBlank() ? xff.split(",")[0].trim()
                        : attrs.getRequest().getRemoteAddr());
            }
        } catch (Exception ignored) {
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
