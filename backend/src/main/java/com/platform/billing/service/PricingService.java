package com.platform.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.billing.entity.BillingRule;
import com.platform.billing.mapper.BillingRuleMapper;
import com.platform.model.entity.ModelEntity;
import com.platform.model.mapper.ModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 成本计价（需求 3.11 / 3.7）：租户 billing_rule 优先（TOKEN/COUNT），未配置回落 model 单价；均未配置为 0。
 * 单价单位：TOKEN 模式为元/千 Token；COUNT 模式为元/次。
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private final BillingRuleMapper ruleMapper;
    private final ModelMapper modelMapper;

    public BigDecimal cost(Long tenantId, String modelName, long inputTokens, long outputTokens) {
        return cost(tenantId, modelName, inputTokens, outputTokens, 0, 0);
    }

    /**
     * 分项计价：输入 Token 拆分为缓存命中/未命中；缓存写入单独计价。
     * 厂商未返回分项时 cached/cacheWrite=0，退化为原“输入×单价 + 输出×单价”。
     */
    public BigDecimal cost(Long tenantId, String modelName, long inputTokens, long outputTokens,
                           long cachedInputTokens, long cacheWriteTokens) {
        if (tenantId == null) return BigDecimal.ZERO;
        long input = Math.max(inputTokens, 0);
        long cached = Math.min(Math.max(cachedInputTokens, 0), input);
        long uncached = input - cached;
        long write = Math.max(cacheWriteTokens, 0);
        long output = Math.max(outputTokens, 0);

        BillingRule rule = findRule(tenantId, modelName);
        if (rule != null) {
            if ("COUNT".equalsIgnoreCase(rule.getBillingMode())) {
                return scale(nz(rule.getPricePerCall()));
            }
            BigDecimal inputPrice = nz(rule.getInputPrice());
            BigDecimal cachedPrice = rule.getCacheReadPrice() == null
                    ? inputPrice : nz(rule.getCacheReadPrice());
            BigDecimal writePrice = rule.getCacheWritePrice() == null
                    ? BigDecimal.ZERO : nz(rule.getCacheWritePrice());
            return scale(part(inputPrice, uncached)
                    .add(part(cachedPrice, cached))
                    .add(part(writePrice, write))
                    .add(part(nz(rule.getOutputPrice()), output)));
        }
        ModelEntity m = modelMapper.findForPricing(modelName, tenantId);
        if (m == null) return BigDecimal.ZERO;
        BigDecimal inputPrice = nz(m.getInputPrice());
        BigDecimal cachedPrice = m.getCacheReadPrice() == null ? inputPrice : nz(m.getCacheReadPrice());
        BigDecimal writePrice = m.getCacheWritePrice() == null ? BigDecimal.ZERO : nz(m.getCacheWritePrice());
        return scale(part(inputPrice, uncached)
                .add(part(cachedPrice, cached))
                .add(part(writePrice, write))
                .add(part(nz(m.getOutputPrice()), output)));
    }

    private BigDecimal part(BigDecimal pricePerThousand, long tokens) {
        return pricePerThousand.multiply(BigDecimal.valueOf(tokens))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
    }

    /** 精确模型规则优先，其次租户默认规则（model_name IS NULL）。 */
    public BillingRule findRule(Long tenantId, String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            BillingRule exact = ruleMapper.selectList(new LambdaQueryWrapper<BillingRule>()
                            .eq(BillingRule::getTenantId, tenantId)
                            .eq(BillingRule::getModelName, modelName)
                            .eq(BillingRule::getStatus, "ENABLED")
                            .orderByDesc(BillingRule::getId)
                            .last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            if (exact != null) return exact;
        }
        return ruleMapper.selectList(new LambdaQueryWrapper<BillingRule>()
                        .eq(BillingRule::getTenantId, tenantId)
                        .isNull(BillingRule::getModelName)
                        .eq(BillingRule::getStatus, "ENABLED")
                        .orderByDesc(BillingRule::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal scale(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }
}
