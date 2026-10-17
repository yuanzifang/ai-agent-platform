package com.platform.billing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 用量看板（需求文档 3.11/7.2）：按日/按模型/按 Agent 汇总 + 明细。
 */
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingUsageMapper usageMapper;

    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(@RequestParam(defaultValue = "30") int days,
                                                  @RequestParam(required = false) Long agentId,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long pageSize) {
        LocalDate since = LocalDate.now().minusDays(Math.min(Math.max(days, 1), 365));
        List<BillingUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<BillingUsage>()
                .ge(BillingUsage::getStatDate, since)
                .eq(agentId != null, BillingUsage::getAgentId, agentId)
                .orderByDesc(BillingUsage::getId));

        long totalIn = 0, totalOut = 0;
        Map<String, long[]> byModel = new LinkedHashMap<>();
        Map<String, long[]> byDay = new TreeMap<>();
        Map<String, long[]> byAgent = new LinkedHashMap<>();
        for (BillingUsage u : rows) {
            long in = u.getInputTokens() == null ? 0 : u.getInputTokens();
            long out = u.getOutputTokens() == null ? 0 : u.getOutputTokens();
            totalIn += in;
            totalOut += out;
            byModel.computeIfAbsent(u.getModelName() == null ? "-" : u.getModelName(),
                    k -> new long[3])[0] += 1;
            byModel.get(u.getModelName() == null ? "-" : u.getModelName())[1] += in;
            byModel.get(u.getModelName() == null ? "-" : u.getModelName())[2] += out;
            String day = String.valueOf(u.getStatDate());
            byDay.computeIfAbsent(day, k -> new long[3])[0] += 1;
            byDay.get(day)[1] += in;
            byDay.get(day)[2] += out;
            String agent = u.getAgentId() == null ? "调试/嵌入" : String.valueOf(u.getAgentId());
            byAgent.computeIfAbsent(agent, k -> new long[3])[0] += 1;
            byAgent.get(agent)[1] += in;
            byAgent.get(agent)[2] += out;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", rows.size());
        summary.put("totalInputTokens", totalIn);
        summary.put("totalOutputTokens", totalOut);
        summary.put("byModel", toList(byModel, "modelName"));
        summary.put("byDay", toList(byDay, "date"));
        summary.put("byAgent", toList(byAgent, "agent"));

        Page<BillingUsage> detail = usageMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<BillingUsage>()
                        .ge(BillingUsage::getStatDate, since)
                        .eq(agentId != null, BillingUsage::getAgentId, agentId)
                        .orderByDesc(BillingUsage::getId));
        List<Map<String, Object>> list = detail.getRecords().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("agentId", u.getAgentId());
            m.put("source", u.getSource());
            m.put("modelName", u.getModelName());
            m.put("inputTokens", u.getInputTokens());
            m.put("outputTokens", u.getOutputTokens());
            m.put("statDate", String.valueOf(u.getStatDate()));
            m.put("createdAt", String.valueOf(u.getCreatedAt()));
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("list", list);
        result.put("total", detail.getTotal());
        result.put("page", page);
        result.put("pageSize", pageSize);
        return ApiResponse.ok(result);
    }

    private List<Map<String, Object>> toList(Map<String, long[]> m, String keyField) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, long[]> e : m.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(keyField, e.getKey());
            item.put("count", e.getValue()[0]);
            item.put("inputTokens", e.getValue()[1]);
            item.put("outputTokens", e.getValue()[2]);
            list.add(item);
        }
        return list;
    }
}
