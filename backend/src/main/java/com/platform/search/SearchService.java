package com.platform.search;

import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.service.AgentService;
import com.platform.agent.service.CaseService;
import com.platform.capability.service.ToolService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.knowledge.service.KnowledgeService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 顶栏全局搜索聚合：复用各模块现有权限过滤，按名称/描述做轻量相关度排序。
 * 不新增跨模块数据旁路。
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final AgentService agentService;
    private final KnowledgeService knowledgeService;
    private final CaseService caseService;
    private final ToolService toolService;

    public Map<String, Object> search(String rawQuery, JwtPrincipal principal) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (query.length() >= 2) {
            var viewer = new KnowledgePermissionService.Viewer(principal.userId(), principal.roles());

            for (AgentRegistry a : agentService.list(query, null, 1, 5, principal).getRecords()) {
                rows.add(row("AGENT", a.getName(), a.getDescription(), "/agent/edit/" + a.getId(), query,
                        a.getName(), a.getDescription()));
            }
            var kbs = knowledgeService.list(query, null, 1, 5, viewer);
            for (Object raw : (List<?>) kbs.getOrDefault("list", List.of())) {
                if (raw instanceof Map<?, ?> kb) {
                    String name = str(kb.get("name"));
                    String desc = str(kb.get("description"));
                    rows.add(row("KB", name, desc, "/knowledge/" + kb.get("id"), query, name, desc));
                }
            }
            var cases = caseService.list(principal, null, null, null, query, null);
            int caseCount = 0;
            for (Object raw : (List<?>) cases.getOrDefault("list", List.of())) {
                if (caseCount++ >= 5) break;
                if (raw instanceof Map<?, ?> c) {
                    String question = str(c.get("question"));
                    String answer = str(c.get("expectedAnswer"));
                    rows.add(row("CASE", question, answer, "/cases", query, question, answer));
                }
            }
            var tools = toolService.list(query, null, null);
            int toolCount = 0;
            for (Object raw : (List<?>) tools.getOrDefault("list", List.of())) {
                if (toolCount++ >= 5) break;
                if (raw instanceof Map<?, ?> t) {
                    String name = str(t.get("name"));
                    String desc = str(t.get("description"));
                    rows.add(row("TOOL", name, desc, "/tools", query, name, desc));
                }
            }
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("score"))
                .reversed()
                .thenComparingInt(r -> typeRank(String.valueOf(r.get("type")))));
        if (rows.size() > 20) rows = rows.subList(0, 20);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("list", rows);
        result.put("total", rows.size());
        return result;
    }

    private Map<String, Object> row(String type, String name, String desc, String path,
                                    String query, String nameText, String descText) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("name", name == null || name.isBlank() ? "(未命名)" : name);
        row.put("desc", desc == null ? "" : desc);
        row.put("path", path);
        row.put("score", score(nameText, descText, query));
        return row;
    }

    private int score(String name, String desc, String query) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String d = desc == null ? "" : desc.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT);
        if (n.equals(q)) return 100;
        if (n.startsWith(q)) return 80;
        if (n.contains(q)) return 60;
        if (d.contains(q)) return 30;
        return 10;
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
