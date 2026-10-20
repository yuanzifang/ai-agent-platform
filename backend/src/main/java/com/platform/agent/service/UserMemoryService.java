package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.UserMemory;
import com.platform.agent.mapper.UserMemoryMapper;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆（需求 3.29 P2）：用户可见、可编辑的事实列表。
 * 只注入当前用户 + 当前 Agent（或全局）的 ACTIVE 记忆，最多 10 条/2000 字符。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private static final int MAX_MEMORIES = 50;
    private static final int MAX_CONTENT = 2000;
    private static final int CONTEXT_LIMIT = 10;

    private final UserMemoryMapper memoryMapper;

    public Map<String, Object> list(JwtPrincipal principal, Long agentId, String keyword) {
        List<UserMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, principal.tenantId())
                .eq(UserMemory::getUserId, principal.userId())
                .eq(UserMemory::getStatus, "ACTIVE")
                .and(agentId != null, w -> w.isNull(UserMemory::getAgentId)
                        .or().eq(UserMemory::getAgentId, agentId))
                .like(keyword != null && !keyword.isBlank(), UserMemory::getContent, keyword)
                .orderByDesc(UserMemory::getUpdatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserMemory m : rows) result.add(view(m));
        return Map.of("list", result, "total", result.size());
    }

    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        long count = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, principal.tenantId())
                .eq(UserMemory::getUserId, principal.userId())
                .eq(UserMemory::getStatus, "ACTIVE"));
        if (count >= MAX_MEMORIES) {
            throw new BusinessException(400, "长期记忆最多保存 " + MAX_MEMORIES + " 条，请先清理");
        }
        UserMemory m = new UserMemory();
        m.setTenantId(principal.tenantId());
        m.setUserId(principal.userId());
        m.setAgentId(toLong(req.get("agentId")));
        m.setCategory(normalizeCategory(req.get("category")));
        m.setContent(requireContent(req.get("content")));
        m.setSource("MANUAL");
        m.setStatus("ACTIVE");
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        memoryMapper.insert(m);
        return view(m);
    }

    public Map<String, Object> update(Long id, Map<String, Object> req, JwtPrincipal principal) {
        UserMemory m = owned(id, principal);
        if (req.containsKey("content")) m.setContent(requireContent(req.get("content")));
        if (req.containsKey("category")) m.setCategory(normalizeCategory(req.get("category")));
        if (req.containsKey("agentId")) m.setAgentId(toLong(req.get("agentId")));
        m.setUpdatedAt(LocalDateTime.now());
        memoryMapper.updateById(m);
        return view(m);
    }

    public void delete(Long id, JwtPrincipal principal) {
        UserMemory m = owned(id, principal);
        memoryMapper.deleteById(m.getId());
    }

    /** 注入对话上下文的记忆文本（空则不注入）。 */
    public String contextFor(Long userId, Long agentId, Long tenantId) {
        if (userId == null || tenantId == null) return "";
        List<UserMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, "ACTIVE")
                .and(w -> w.isNull(UserMemory::getAgentId)
                        .or().eq(agentId != null, UserMemory::getAgentId, agentId))
                .orderByDesc(UserMemory::getUpdatedAt)
                .last("LIMIT " + CONTEXT_LIMIT));
        StringBuilder sb = new StringBuilder();
        appendGroup(sb, "用户画像", rows, "PROFILE");
        appendGroup(sb, "用户偏好", rows, "PREFERENCE");
        appendGroup(sb, "其他稳定事实", rows, "FACT");
        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String title, List<UserMemory> rows, String category) {
        List<String> lines = new ArrayList<>();
        for (UserMemory m : rows) {
            if (!category.equals(m.getCategory())) continue;
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            lines.add("- " + m.getContent().trim());
        }
        if (lines.isEmpty()) return;
        String header = "【" + title + "】\n";
        if (sb.length() + header.length() > 2000) return;
        sb.append(header);
        for (String line : lines) {
            if (sb.length() + line.length() + 1 > 2000) return;
            sb.append(line).append('\n');
        }
    }

    /** 用户画像视图：按类别聚合 + 自动生成统计。 */
    public Map<String, Object> profile(JwtPrincipal principal) {
        List<UserMemory> rows = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, principal.tenantId())
                .eq(UserMemory::getUserId, principal.userId())
                .eq(UserMemory::getStatus, "ACTIVE")
                .orderByDesc(UserMemory::getUpdatedAt));
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("PROFILE", new ArrayList<>());
        categories.put("PREFERENCE", new ArrayList<>());
        categories.put("FACT", new ArrayList<>());
        int auto = 0;
        List<String> summaryParts = new ArrayList<>();
        for (UserMemory m : rows) {
            String category = categories.containsKey(m.getCategory()) ? m.getCategory() : "FACT";
            categories.get(category).add(m.getContent());
            if ("AUTO".equalsIgnoreCase(m.getSource())) auto++;
            if (summaryParts.size() < 5 && List.of("PROFILE", "PREFERENCE").contains(category)) {
                summaryParts.add(m.getContent());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summaryParts.isEmpty() ? "" : String.join("；", summaryParts));
        result.put("categories", categories);
        result.put("total", rows.size());
        result.put("autoCount", auto);
        return result;
    }

    private UserMemory owned(Long id, JwtPrincipal principal) {
        UserMemory m = memoryMapper.selectById(id);
        if (m == null || !m.getUserId().equals(principal.userId())
                || !m.getTenantId().equals(principal.tenantId())) {
            throw new BusinessException(404, "记忆不存在");
        }
        return m;
    }

    private String requireContent(Object raw) {
        String content = raw == null ? "" : String.valueOf(raw).trim();
        if (content.isEmpty()) throw new BusinessException(400, "记忆内容不能为空");
        if (content.length() > MAX_CONTENT) {
            content = content.substring(0, MAX_CONTENT);
        }
        return content;
    }

    private String normalizeCategory(Object raw) {
        String category = raw == null ? "FACT" : String.valueOf(raw).toUpperCase();
        if (!List.of("FACT", "PREFERENCE", "PROFILE", "OTHER").contains(category)) category = "OTHER";
        return category;
    }

    private Long toLong(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            long value = Long.parseLong(String.valueOf(raw));
            return value > 0 ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> view(UserMemory m) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", m.getId());
        view.put("agentId", m.getAgentId());
        view.put("category", m.getCategory());
        view.put("content", m.getContent());
        view.put("source", m.getSource());
        view.put("status", m.getStatus());
        view.put("createdAt", m.getCreatedAt());
        view.put("updatedAt", m.getUpdatedAt());
        return view;
    }
}
