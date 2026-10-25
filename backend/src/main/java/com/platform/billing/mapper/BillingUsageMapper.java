package com.platform.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.billing.entity.BillingUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface BillingUsageMapper extends BaseMapper<BillingUsage> {

    /** 租户累计成本（配额校验用，避免全表拉取到内存求和）。 */
    @Select("SELECT COALESCE(SUM(cost), 0) FROM billing_usage WHERE tenant_id = #{tenantId}")
    BigDecimal sumCostByTenant(@Param("tenantId") Long tenantId);

    /** 成员今日已用 Token（人员维度配额）。 */
    @Select("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM billing_usage "
            + "WHERE user_id = #{userId} AND stat_date = CURDATE()")
    long sumTokensTodayByUser(@Param("userId") Long userId);

    /** 成员本月消费合计（人员维度金额上限）。 */
    @Select("SELECT COALESCE(SUM(cost), 0) FROM billing_usage "
            + "WHERE user_id = #{userId} AND stat_date >= #{monthStart}")
    java.math.BigDecimal sumMonthCostByUser(@Param("userId") Long userId,
                                            @Param("monthStart") java.time.LocalDate monthStart);

    /** 今日已用 Token（输入+输出，含全部 source）。 */
    @Select("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM billing_usage "
            + "WHERE tenant_id = #{tenantId} AND stat_date = CURDATE()")
    long sumTokensToday(@Param("tenantId") Long tenantId);
}
