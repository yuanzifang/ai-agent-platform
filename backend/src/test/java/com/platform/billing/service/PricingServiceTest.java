package com.platform.billing.service;

import com.platform.billing.entity.BillingRule;
import com.platform.billing.mapper.BillingRuleMapper;
import com.platform.model.entity.ModelEntity;
import com.platform.model.mapper.ModelMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 缓存分项计价：未命中输入、缓存命中、缓存写入、输出分开计算。 */
class PricingServiceTest {

    @Test
    void ruleWithCachePrices() {
        BillingRuleMapper ruleMapper = mock(BillingRuleMapper.class);
        ModelMapper modelMapper = mock(ModelMapper.class);
        BillingRule rule = new BillingRule();
        rule.setBillingMode("TOKEN");
        rule.setInputPrice(new BigDecimal("0.002"));
        rule.setOutputPrice(new BigDecimal("0.008"));
        rule.setCacheReadPrice(new BigDecimal("0.0002"));
        rule.setCacheWritePrice(new BigDecimal("0.001"));
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        PricingService service = new PricingService(ruleMapper, modelMapper);

        BigDecimal cost = service.cost(1L, "deepseek-flash", 1000, 100, 600, 100);
        // 400*0.002/1000 + 600*0.0002/1000 + 100*0.001/1000 + 100*0.008/1000
        assertEquals(new BigDecimal("0.001820"), cost);
    }

    @Test
    void cachePriceFallsBackToInputPrice() {
        BillingRuleMapper ruleMapper = mock(BillingRuleMapper.class);
        ModelMapper modelMapper = mock(ModelMapper.class);
        BillingRule rule = new BillingRule();
        rule.setBillingMode("TOKEN");
        rule.setInputPrice(new BigDecimal("0.002"));
        rule.setOutputPrice(new BigDecimal("0.008"));
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        PricingService service = new PricingService(ruleMapper, modelMapper);

        BigDecimal cost = service.cost(1L, "deepseek-flash", 1000, 0, 600, 0);
        // 缓存价未配置：按输入价计算，400*0.002 + 600*0.002 = 0.002
        assertEquals(new BigDecimal("0.002000"), cost);
    }

    @Test
    void modelFallbackWithCachePrice() {
        BillingRuleMapper ruleMapper = mock(BillingRuleMapper.class);
        ModelMapper modelMapper = mock(ModelMapper.class);
        when(ruleMapper.selectList(any())).thenReturn(List.of());
        ModelEntity model = new ModelEntity();
        model.setInputPrice(new BigDecimal("0.002"));
        model.setOutputPrice(new BigDecimal("0.008"));
        model.setCacheReadPrice(new BigDecimal("0.0002"));
        when(modelMapper.findForPricing("deepseek-flash", 1L)).thenReturn(model);
        PricingService service = new PricingService(ruleMapper, modelMapper);

        BigDecimal cost = service.cost(1L, "deepseek-flash", 1000, 100, 600, 0);
        assertEquals(new BigDecimal("0.001720"), cost);
    }
}
