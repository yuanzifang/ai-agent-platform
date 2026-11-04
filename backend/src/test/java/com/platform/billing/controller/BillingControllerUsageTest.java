package com.platform.billing.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.BillingService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 成员自助用量接口：总额、分项 Token、成本与明细字段。 */
@ExtendWith(MockitoExtension.class)
class BillingControllerUsageTest {

    @Mock
    private BillingUsageMapper usageMapper;

    @Mock
    private BillingService billingService;

    private BillingController controller;

    @BeforeEach
    void setUp() {
        controller = new BillingController(usageMapper, billingService);
    }

    @Test
    void myUsageReturnsCurrentUserTotalsAndTokenBreakdown() {
        BillingUsage row = new BillingUsage();
        row.setId(9L);
        row.setTenantId(1L);
        row.setUserId(7L);
        row.setAgentId(3L);
        row.setSource("chat");
        row.setModelName("qwen2.5:7b");
        row.setInputTokens(1000L);
        row.setOutputTokens(200L);
        row.setCachedInputTokens(300L);
        row.setCacheWriteTokens(40L);
        row.setReasoningTokens(10L);
        row.setCost(new BigDecimal("0.0123"));
        row.setStatDate(LocalDate.now());

        when(usageMapper.selectList(any())).thenReturn(List.of(row));
        Page<BillingUsage> page = new Page<>(1, 20);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(usageMapper.selectPage(any(Page.class), any())).thenReturn(page);

        JwtPrincipal principal = new JwtPrincipal(7L, 1L, false, List.of(), List.of());
        ApiResponse<Map<String, Object>> response = controller.myUsage(30, null, 1, 20, principal);

        assertEquals(0, response.code());
        Map<String, Object> data = response.data();
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(1, summary.get("totalRequests"));
        assertEquals(1000L, summary.get("totalInputTokens"));
        assertEquals(200L, summary.get("totalOutputTokens"));
        assertEquals(300L, summary.get("totalCachedInputTokens"));
        assertEquals(40L, summary.get("totalCacheWriteTokens"));
        assertEquals(10L, summary.get("totalReasoningTokens"));
        assertEquals(1550L, summary.get("totalTokens"));
        assertEquals(new BigDecimal("0.0123"), summary.get("totalCost"));

        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertEquals(7L, list.get(0).get("userId"));
        assertEquals(40L, list.get(0).get("cacheWriteTokens"));
    }
}
