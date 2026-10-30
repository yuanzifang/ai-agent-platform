package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.UserMemory;
import com.platform.agent.mapper.UserMemoryMapper;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        Set<String> seen = new HashSet<>();
        appendGroup(sb, "用户画像", rows, "PROFILE", seen);
        appendGroup(sb, "用户偏好", rows, "PREFERENCE", seen);
        appendGroup(sb, "其他稳定事实", rows, "FACT", seen);
        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String title, List<UserMemory> rows, String category,
                             Set<String> seen) {
        List<String> lines = new ArrayList<>();
        for (UserMemory m : rows) {
            if (!category.equals(m.getCategory())) continue;
            if (!meaningful(m.getContent())) continue;
            String content = m.getContent().trim();
            if (!seen.add(canonical(content))) continue;
            lines.add("- " + content);
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
        Set<String> seen = new HashSet<>();
        int auto = 0;
        int total = 0;
        List<String> summaryParts = new ArrayList<>();
        for (UserMemory m : rows) {
            if (!meaningful(m.getContent())) continue;
            String content = m.getContent().trim();
            if (!seen.add(canonical(content))) continue;
            String category = categories.containsKey(m.getCategory()) ? m.getCategory() : "FACT";
            categories.get(category).add(content);
            total++;
            if ("AUTO".equalsIgnoreCase(m.getSource())) auto++;
            if (summaryParts.size() < 5 && List.of("PROFILE", "PREFERENCE").contains(category)) {
                summaryParts.add(content);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summaryParts.isEmpty() ? "" : String.join("；", summaryParts));
        result.put("categories", categories);
        result.put("total", total);
        result.put("autoCount", auto);
        return result;
    }

    /** 展示层去重：忽略空白差异，避免同名事实在摘要/JSON 中重复出现。 */
    private String canonical(String content) {
        return content.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 过滤“无/未知/未提供”以及抽取器误收进记忆的对话原文。 */
    private boolean meaningful(String content) {
        if (content == null || content.isBlank()) return false;
        String text = content.trim();
        if (text.startsWith("用户消息：") || text.startsWith("助手回答：")
                || text.startsWith("建议您") || text.startsWith("请确认")) return false;
        String value = text;
        int idx = Math.max(text.lastIndexOf('：'), text.lastIndexOf(':'));
        if (idx >= 0 && idx < text.length() - 1) value = text.substring(idx + 1).trim();
        return !Set.of("无", "未知", "未提供", "暂无", "不适用", "null", "none")
                .contains(value.toLowerCase(Locale.ROOT));
    }

    /** 全量替换用户画像：清理重复/噪声后将编辑结果重新落库。 */
    @Transactional
    public Map<String, Object> replaceProfile(Map<String, Object> req, JwtPrincipal principal) {
        Object rawItems = req.get("items");
        if (!(rawItems instanceof List<?> items)) {
            throw new BusinessException(400, "画像条目格式错误");
        }
        List<UserMemory> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item)) continue;
            String content = requireContent(item.get("content"));
            if (!meaningful(content)) continue;
            String key = canonical(content);
            if (!seen.add(key)) continue;
            if (rows.size() >= MAX_MEMORIES) {
                throw new BusinessException(400, "长期记忆最多保存 " + MAX_MEMORIES + " 条");
            }
            UserMemory m = new UserMemory();
            m.setTenantId(principal.tenantId());
            m.setUserId(principal.userId());
            m.setAgentId(toLong(item.get("agentId")));
            m.setCategory(normalizeCategory(item.get("category")));
            m.setContent(content);
            m.setSource("MANUAL");
            m.setStatus("ACTIVE");
            m.setCreatedAt(LocalDateTime.now());
            m.setUpdatedAt(LocalDateTime.now());
            rows.add(m);
        }
        memoryMapper.delete(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, principal.tenantId())
                .eq(UserMemory::getUserId, principal.userId())
                .eq(UserMemory::getStatus, "ACTIVE"));
        for (UserMemory row : rows) memoryMapper.insert(row);
        return profile(principal);
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
