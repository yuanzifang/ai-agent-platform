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
        if (tenantId == null) return BigDecimal.ZERO;
        BillingRule rule = findRule(tenantId, modelName);
        if (rule != null) {
            if ("COUNT".equalsIgnoreCase(rule.getBillingMode())) {
                return scale(nz(rule.getPricePerCall()));
            }
            BigDecimal in = nz(rule.getInputPrice()).multiply(BigDecimal.valueOf(Math.max(inputTokens, 0)))
                    .divide(THOUSAND, 6, RoundingMode.HALF_UP);
            BigDecimal out = nz(rule.getOutputPrice()).multiply(BigDecimal.valueOf(Math.max(outputTokens, 0)))
                    .divide(THOUSAND, 6, RoundingMode.HALF_UP);
            return scale(in.add(out));
        }
        ModelEntity m = modelMapper.findForPricing(modelName, tenantId);
        if (m == null) return BigDecimal.ZERO;
        BigDecimal in = nz(m.getInputPrice()).multiply(BigDecimal.valueOf(Math.max(inputTokens, 0)))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        BigDecimal out = nz(m.getOutputPrice()).multiply(BigDecimal.valueOf(Math.max(outputTokens, 0)))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        return scale(in.add(out));
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
