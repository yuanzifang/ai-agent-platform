package com.platform.capability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.capability.entity.ToolApproval;
import com.platform.capability.entity.ToolRegistry;
import com.platform.capability.mapper.ToolApprovalMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 写工具逐次审批的持久化与状态流转；工具执行由 Controller 调用 ToolRuntimeService 完成，避免循环依赖。 */
@Service
@RequiredArgsConstructor
public class ToolApprovalService {

    private final ToolApprovalMapper mapper;

    public ToolApproval request(Long agentId, ToolRegistry tool, String input) {
        ToolApproval a = new ToolApproval();
        a.setTenantId(TenantContext.getTenantId());
        a.setAgentId(agentId);
        a.setToolId(tool.getId());
        a.setToolKey(tool.getToolKey());
        a.setToolName(tool.getName());
        a.setToolType(tool.getToolType());
        a.setToolInput(input == null ? "" : input.substring(0, Math.min(input.length(), 8000)));
        a.setStatus("PENDING");
        a.setCreatedAt(LocalDateTime.now());
        mapper.insert(a);
        return a;
    }

    public Map<String, Object> list(String status, long page, long pageSize) {
        long size = Math.max(1, Math.min(pageSize, 100));
        long current = Math.max(1, page);
        var q = new LambdaQueryWrapper<ToolApproval>()
                .eq(status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status),
                        ToolApproval::getStatus, status == null ? null : status.toUpperCase())
                .orderByDesc(ToolApproval::getId)
                .last("LIMIT " + ((current - 1) * size) + "," + size);
        List<ToolApproval> rows = mapper.selectList(q);
        Long total = mapper.selectCount(new LambdaQueryWrapper<ToolApproval>()
                .eq(status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status),
                        ToolApproval::getStatus, status == null ? null : status.toUpperCase()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows.stream().map(this::view).toList());
        result.put("total", total == null ? 0 : total);
        result.put("page", current);
        result.put("pageSize", size);
        return result;
    }

    /** 原子认领：只有 PENDING 能进入审批结果，防并发重复执行。 */
    public ToolApproval claim(Long id, boolean approve, String comment, JwtPrincipal principal) {
        ToolApproval row = mapper.selectById(id);
        if (row == null) throw new BusinessException(404, "审批不存在");
        int updated = mapper.update(null, new LambdaUpdateWrapper<ToolApproval>()
                .eq(ToolApproval::getId, id)
                .eq(ToolApproval::getStatus, "PENDING")
                .set(ToolApproval::getStatus, approve ? "APPROVED" : "REJECTED")
                .set(ToolApproval::getDecidedBy, principal.userId())
                .set(ToolApproval::getDecidedAt, LocalDateTime.now())
                .set(ToolApproval::getComment, comment == null ? null
                        : comment.substring(0, Math.min(comment.length(), 1000))));
        if (updated == 0) throw new BusinessException(409, "该审批已处理");
        row.setStatus(approve ? "APPROVED" : "REJECTED");
        row.setDecidedBy(principal.userId());
        row.setDecidedAt(LocalDateTime.now());
        row.setComment(comment);
        return row;
    }

    public void finish(Long id, String status, String result) {
        mapper.update(null, new LambdaUpdateWrapper<ToolApproval>()
                .eq(ToolApproval::getId, id)
                .set(ToolApproval::getStatus, status)
                .set(ToolApproval::getResult, result == null ? null
                        : result.substring(0, Math.min(result.length(), 20000))));
    }

    private Map<String, Object> view(ToolApproval a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("agentId", a.getAgentId());
        m.put("toolId", a.getToolId());
        m.put("toolKey", a.getToolKey());
        m.put("toolName", a.getToolName());
        m.put("toolType", a.getToolType());
        m.put("toolInput", a.getToolInput());
        m.put("status", a.getStatus());
        m.put("requestedBy", a.getRequestedBy());
        m.put("decidedBy", a.getDecidedBy());
        m.put("comment", a.getComment());
        m.put("result", a.getResult());
        m.put("createdAt", a.getCreatedAt());
        m.put("decidedAt", a.getDecidedAt());
        return m;
    }
}
