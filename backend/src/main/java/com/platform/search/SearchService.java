package com.platform.search;

import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.service.AgentService;
import com.platform.agent.service.CaseService;
import com.platform.capability.service.ToolService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.knowledge.service.KnowledgeService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 顶栏全局搜索聚合：复用各模块现有权限过滤，按名称/描述做轻量相关度排序。
 * 查询词由中文分析器切分，名称命中优先于描述命中；返回内容带安全的高亮 HTML。
 * 不新增跨模块数据旁路。
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final AgentService agentService;
    private final KnowledgeService knowledgeService;
    private final CaseService caseService;
    private final ToolService toolService;
    private final StringRedisTemplate redis;

    public Map<String, Object> search(String rawQuery, JwtPrincipal principal) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> terms = ChineseTextAnalyzer.tokenize(query);
        if (query.length() >= 2) {
            var viewer = new KnowledgePermissionService.Viewer(principal.userId(), principal.roles());

            for (AgentRegistry a : agentService.list(query, null, 1, 5, principal).getRecords()) {
                rows.add(row("AGENT", a.getId(), a.getName(), a.getDescription(),
                        "/agent/edit/" + a.getId(), query, terms));
            }
            var kbs = knowledgeService.list(query, null, 1, 5, viewer);
            for (Object raw : (List<?>) kbs.getOrDefault("list", List.of())) {
                if (raw instanceof Map<?, ?> kb) {
                    String name = str(kb.get("name"));
                    String desc = str(kb.get("description"));
                    rows.add(row("KB", kb.get("id"), name, desc,
                            "/knowledge/" + kb.get("id"), query, terms));
                }
            }
            var cases = caseService.list(principal, null, null, null, query, null);
            int caseCount = 0;
            for (Object raw : (List<?>) cases.getOrDefault("list", List.of())) {
                if (caseCount++ >= 5) break;
                if (raw instanceof Map<?, ?> c) {
                    String question = str(c.get("question"));
                    String answer = str(c.get("expectedAnswer"));
                    rows.add(row("CASE", c.get("id"), question, answer, "/cases", query, terms));
                }
            }
            var tools = toolService.list(query, null, null);
            int toolCount = 0;
            for (Object raw : (List<?>) tools.getOrDefault("list", List.of())) {
                if (toolCount++ >= 5) break;
                if (raw instanceof Map<?, ?> t) {
                    String name = str(t.get("name"));
                    String desc = str(t.get("description"));
                    rows.add(row("TOOL", t.get("id"), name, desc, "/tools", query, terms));
                }
            }
        }
        applyClickFeedback(rows, principal);
        rows.sort(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("score"))
                .reversed()
                .thenComparingInt(r -> typeRank(String.valueOf(r.get("type")))));
        if (rows.size() > 20) rows = rows.subList(0, 20);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("tokens", terms);
        result.put("list", rows);
        if (rows.isEmpty()) {
            String suggestion = suggestion(query, principal, terms);
            if (suggestion != null) result.put("suggestion", suggestion);
        }
        result.put("total", rows.size());
        return result;
    }

    private Map<String, Object> row(String type, Object id, String name, String desc, String path,
                                    String query, List<String> terms) {
        String displayName = name == null || name.isBlank() ? "(未命名)" : name;
        String displayDesc = desc == null ? "" : desc;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("id", id);
        row.put("name", displayName);
        row.put("desc", displayDesc);
        // nameHtml/descHtml 已经过 HTML 转义，只含后端生成的 <mark>，可安全交给前端 v-html。
        row.put("nameHtml", SearchHighlighter.highlight(displayName, terms));
        row.put("descHtml", SearchHighlighter.highlight(displayDesc, terms));
        row.put("path", path);
        row.put("score", score(displayName, displayDesc, query, terms));
        return row;
    }

    private int score(String name, String desc, String query, List<String> terms) {
        String n = ChineseTextAnalyzer.normalize(name);
        String d = ChineseTextAnalyzer.normalize(desc);
        String q = ChineseTextAnalyzer.normalize(query);
        int score = 0;

        // 完整查询命中，名称权重最高；用于保证“客服助手”整体匹配优先于只命中“客服”。
        if (!q.isEmpty()) {
            if (n.equals(q)) score += 140;
            else if (n.startsWith(q)) score += 100;
            else if (n.contains(q)) score += 70;
            if (d.equals(q)) score += 50;
            else if (d.contains(q)) score += 30;
        }

        // 分词命中：名称前缀/包含 > 描述包含；覆盖率作为多词查询的轻量相关度加成。
        int matched = 0;
        for (String term : terms) {
            boolean hit = false;
            if (n.equals(term)) {
                score += 45;
                hit = true;
            } else if (n.startsWith(term)) {
                score += 32;
                hit = true;
            } else if (n.contains(term)) {
                score += 22;
                hit = true;
            }
            if (d.contains(term)) {
                score += 12;
                hit = true;
            }
            if (hit) matched++;
        }
        if (!terms.isEmpty() && terms.size() > 1) {
            score += (int) Math.round(30.0 * matched / terms.size());
        }
        return score;
    }

    /** 无结果时从权限内实体名称找最近词，返回给前端做“你是不是想搜”。 */
    private String suggestion(String query, JwtPrincipal principal, List<String> terms) {
        if (query == null || query.length() < 2 || principal == null) return null;
        String normalizedQuery = ChineseTextAnalyzer.normalize(query);
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        try {
            var viewer = new KnowledgePermissionService.Viewer(principal.userId(), principal.roles());
            for (AgentRegistry a : agentService.list("", null, 1, 100, principal).getRecords()) {
                if (a.getName() != null) names.add(a.getName());
            }
            var kbs = knowledgeService.list("", null, 1, 100, viewer);
            for (Object raw : (List<?>) kbs.getOrDefault("list", List.of())) {
                if (raw instanceof Map<?, ?> kb && kb.get("name") != null) names.add(str(kb.get("name")));
            }
            var cases = caseService.list(principal, null, null, null, "", null);
            for (Object raw : (List<?>) cases.getOrDefault("list", List.of())) {
                if (raw instanceof Map<?, ?> c && c.get("question") != null) names.add(str(c.get("question")));
            }
            var tools = toolService.list("", null, null);
            for (Object raw : (List<?>) tools.getOrDefault("list", List.of())) {
                if (raw instanceof Map<?, ?> t && t.get("name") != null) names.add(str(t.get("name")));
            }
        } catch (Exception ignored) {
            return null;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String name : names) {
            String candidate = ChineseTextAnalyzer.normalize(name);
            if (candidate.isBlank() || candidate.equals(normalizedQuery)) continue;
            int distance = levenshtein(normalizedQuery, candidate);
            if (distance < bestDistance || (distance == bestDistance && best != null
                    && name.length() < best.length())) {
                bestDistance = distance;
                best = name;
            }
        }
        // 两字查询只接受极近的候选，避免把“客服”推荐成完全无关的长名称。
        int maxDistance = normalizedQuery.length() <= 2 ? 1 : 2;
        return bestDistance <= maxDistance ? best : null;
    }

    private int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    /** 记录一次结果点击；Redis 不可用时静默降级，不影响搜索。 */
    public void feedback(String type, Object id, String rawQuery, JwtPrincipal principal) {
        if (principal == null || id == null) return;
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        if (!List.of("AGENT", "KB", "CASE", "TOOL").contains(normalizedType)) return;
        String key = feedbackKey(principal, normalizedType, String.valueOf(id));
        try {
            redis.opsForValue().increment(key);
            redis.expire(key, java.time.Duration.ofDays(30));
        } catch (Exception ignored) {
            // 点击反馈是排序增强，Redis 故障不能影响搜索本身。
        }
    }

    private void applyClickFeedback(List<Map<String, Object>> rows, JwtPrincipal principal) {
        if (rows.isEmpty() || principal == null) return;
        for (Map<String, Object> row : rows) {
            try {
                String key = feedbackKey(principal, String.valueOf(row.get("type")), String.valueOf(row.get("id")));
                String value = redis.opsForValue().get(key);
                int clicks = value == null ? 0 : Integer.parseInt(value);
                if (clicks > 0) {
                    row.put("score", (Integer) row.get("score") + Math.min(30, clicks * 3));
                }
            } catch (Exception ignored) {
                // ignore feedback cache failures
            }
        }
    }

    private String feedbackKey(JwtPrincipal principal, String type, String id) {
        String tenant = principal.tenantId() == null ? "platform" : String.valueOf(principal.tenantId());
        return "search:click:" + tenant + ":" + type + ":" + id;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int typeRank(String type) {
        return switch (type) {
            case "AGENT" -> 0;
            case "KB" -> 1;
            case "CASE" -> 2;
            default -> 3;
        };
    }
}
