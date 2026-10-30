package com.platform.observability.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GrayEvalCompareService {

    private final JdbcTemplate jdbcTemplate;

    public GrayEvalCompareService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Value("${gray.compare.max-days:90}")
    private int maxDays;

    public List<Map<String, Object>> evalCompare(Long agentId, int days) {
        int d = Math.min(Math.max(days, 1), Math.max(1, maxDays));
        var start = LocalDateTime.now().minusDays(d);
        var params = new ArrayList<Object>(List.of(tenantId(), start));
        String agentCond = agentId == null ? "" : " AND et.agent_id = ? ";
        if (agentId != null) params.add(agentId);

        String sql = "SELECT et.dataset_id, ed.name AS dataset_name, "
                + "CASE WHEN et.gray_tag IS NULL OR et.gray_tag = '' THEN 'baseline' ELSE 'gray' END AS grp, "
                + "COALESCE(er.step_type, 'OVERALL') AS step_type, "
                + "COUNT(er.id) AS cnt, "
                + "COALESCE(AVG(er.score), 0) AS avg_score, "
                + "COALESCE(SUM(CASE WHEN er.passed = 1 THEN 1 ELSE 0 END), 0) AS passed "
                + "FROM eval_result er "
                + "JOIN eval_task et ON er.task_id = et.id "
                + "LEFT JOIN eval_dataset ed ON et.dataset_id = ed.id "
                + "WHERE et.tenant_id = ? AND et.created_at >= ? "
                + "AND er.score IS NOT NULL AND et.dataset_id IS NOT NULL "
                + agentCond
                + "GROUP BY et.dataset_id, ed.name, grp, er.step_type "
                + "ORDER BY et.dataset_id, er.step_type, grp";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());

        // dsId → { dsName, baseline{...}, gray{...} }
        var byDs = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : rows) {
            String dsId = String.valueOf(row.get("dataset_id"));
            String grp = String.valueOf(row.get("grp"));
            String stepType = str(row.get("step_type"));
            long cnt = ((Number) row.get("cnt")).longValue();
            double avgScore = ((Number) row.get("avg_score")).doubleValue();
            long passed = row.get("passed") instanceof Number n ? n.longValue() : 0;

            var ds = byDs.computeIfAbsent(dsId, k -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("datasetId", dsId);
                m.put("datasetName", row.get("dataset_name"));
                return m;
            });
            var side = checkedMap(ds, grp);
            side.put("items", ((Number) side.getOrDefault("items", 0)).longValue() + cnt);
            side.put("avgScore", avgScore);
            if (stepType == null || stepType.isBlank() || "OVERALL".equals(stepType)) {
                side.put("passRate", cnt > 0 ? passed / (double) cnt : 0.0);
            } else {
                @SuppressWarnings("unchecked")
                var byStep = (Map<String, Object>) checkedMap(side, "byStepType");
                @SuppressWarnings("unchecked")
                var st = (Map<String, Object>) checkedMap(byStep, stepType);
                st.put("cnt", ((Number) st.getOrDefault("cnt", 0)).longValue() + cnt);
                st.put("avgScore", avgScore);
                st.put("passRate", cnt > 0 ? passed / (double) cnt : 0.0);
            }
        }

        // 清理 byStepType 空条目
        var result = new ArrayList<Map<String, Object>>();
        for (var ds : byDs.values()) {
            for (String grp : List.of("baseline", "gray")) {
                if (ds.get(grp) instanceof Map<?, ?> side) {
                    var byStep = side.get("byStepType");
                    if (byStep instanceof Map<?, ?> bs && bs.isEmpty()) side.remove("byStepType");
                }
            }
            result.add(ds);
        }
        return result;
    }

    /** 安全取嵌套 Map（不存在则创建并挂载）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkedMap(Map<String, Object> parent, String key) {
        if (parent.get(key) instanceof Map<?, ?> m) return (Map<String, Object>) m;
        var m = new LinkedHashMap<String, Object>();
        parent.put(key, m);
        return m;
    }

    private Long tenantId() {
        var tid = com.platform.common.context.TenantContext.getTenantId();
        if (tid == null) throw new com.platform.common.exception.BusinessException(403, "缺少租户上下文");
        return tid;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
