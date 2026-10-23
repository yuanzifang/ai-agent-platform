package com.platform.billing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.BillingService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 计费管理接口（需求 3.11 / 7.2）：用量、计费规则、账户/充值、账单/导出、配额。
 */
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "用量计费")
public class BillingController {

    private final BillingUsageMapper usageMapper;
    private final BillingService billingService;

    // ---------- 用量 ----------

    @GetMapping("/usage")
    @PreAuthorize("hasAuthority('billing:read')")
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
        BigDecimal totalCost = BigDecimal.ZERO;
        Map<String, Object[]> byModel = new LinkedHashMap<>();
        Map<String, Object[]> byDay = new TreeMap<>();
        Map<String, Object[]> byAgent = new LinkedHashMap<>();
        for (BillingUsage u : rows) {
            long in = u.getInputTokens() == null ? 0 : u.getInputTokens();
            long out = u.getOutputTokens() == null ? 0 : u.getOutputTokens();
            BigDecimal cost = u.getCost() == null ? BigDecimal.ZERO : u.getCost();
            totalIn += in;
            totalOut += out;
            totalCost = totalCost.add(cost);
            String model = u.getModelName() == null ? "-" : u.getModelName();
            accumulate(byModel, model, in, out, cost);
            accumulate(byDay, String.valueOf(u.getStatDate()), in, out, cost);
            accumulate(byAgent, u.getAgentId() == null ? "调试/嵌入" : String.valueOf(u.getAgentId()), in, out, cost);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", rows.size());
        summary.put("totalInputTokens", totalIn);
        summary.put("totalOutputTokens", totalOut);
        summary.put("totalCost", totalCost);
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
            m.put("knowledgeBaseId", u.getKnowledgeBaseId());
            m.put("source", u.getSource());
            m.put("modelName", u.getModelName());
            m.put("inputTokens", u.getInputTokens());
            m.put("outputTokens", u.getOutputTokens());
            m.put("cost", u.getCost());
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

    private void accumulate(Map<String, Object[]> map, String key, long in, long out, BigDecimal cost) {
        Object[] v = map.computeIfAbsent(key, k -> new Object[]{0L, 0L, 0L, BigDecimal.ZERO});
        v[0] = (Long) v[0] + 1;
        v[1] = (Long) v[1] + in;
        v[2] = (Long) v[2] + out;
        v[3] = ((BigDecimal) v[3]).add(cost);
    }

    private List<Map<String, Object>> toList(Map<String, Object[]> m, String keyField) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : m.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(keyField, e.getKey());
            item.put("count", e.getValue()[0]);
            item.put("inputTokens", e.getValue()[1]);
            item.put("outputTokens", e.getValue()[2]);
            item.put("cost", e.getValue()[3]);
            list.add(item);
        }
        return list;
    }

    // ---------- 计费规则 ----------

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('billing:read')")
    public ApiResponse<Map<String, Object>> rules() {
        return ApiResponse.ok(billingService.rules());
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('model:manage')")
    public ApiResponse<Map<String, Object>> createRule(@RequestBody Map<String, Object> req,
                                                       @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(billingService.createRule(req, principal));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('model:manage')")
    public ApiResponse<Void> updateRule(@PathVariable Long id, @RequestBody Map<String, Object> req,
                                        @AuthenticationPrincipal JwtPrincipal principal) {
        billingService.updateRule(id, req, principal);
        return ApiResponse.ok();
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('model:manage')")
    public ApiResponse<Void> deleteRule(@PathVariable Long id,
                                        @AuthenticationPrincipal JwtPrincipal principal) {
        billingService.deleteRule(id, principal);
        return ApiResponse.ok();
    }

    // ---------- 账户 / 充值 ----------

    @GetMapping("/account")
    @PreAuthorize("hasAuthority('billing:read')")
    public ApiResponse<Map<String, Object>> account() {
        return ApiResponse.ok(billingService.account());
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('billing:read')")
    public ApiResponse<Map<String, Object>> transactions(@RequestParam(defaultValue = "1") long page,
                                                         @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(billingService.transactions(page, pageSize));
    }

    public record RechargeRequest(BigDecimal amount, String note) {
    }

    @PostMapping("/recharge")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Map<String, Object>> recharge(@RequestBody RechargeRequest req,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(billingService.recharge(req.amount(), req.note(), principal));
    }

    // ---------- 账单 ----------

    @GetMapping("/bills")
    @PreAuthorize("hasAuthority('billing:read')")
    public ApiResponse<Map<String, Object>> bills() {
        return ApiResponse.ok(billingService.bills());
    }

    public record GenerateBillRequest(String period) {
    }

    @PostMapping("/bills/generate")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Map<String, Object>> generateBill(@RequestBody(required = false) GenerateBillRequest req,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(billingService.generateBill(req == null ? null : req.period(), principal));
    }

    @GetMapping("/bills/{id}")
    @PreAuthorize("hasAuthority('billing:read')")
    public ApiResponse<Map<String, Object>> billDetail(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(billingService.billDetail(id, page, pageSize));
    }

    @GetMapping("/bills/{id}/export")
    @PreAuthorize("hasAuthority('billing:read')")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        BillingService.CsvFile file = billingService.exportCsv(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file.content());
    }

    // ---------- 配额 ----------

    @GetMapping("/quota")
    @PreAuthorize("hasAuthority('billing:read')")
    public ApiResponse<Map<String, Object>> quota() {
        return ApiResponse.ok(billingService.quota());
    }

    @PutMapping("/quota")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Map<String, Object>> updateQuota(@RequestBody Map<String, Object> req,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(billingService.updateQuota(req, principal));
    }
}
