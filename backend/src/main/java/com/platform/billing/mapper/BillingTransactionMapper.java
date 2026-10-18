package com.platform.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.billing.entity.BillingTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillingTransactionMapper extends BaseMapper<BillingTransaction> {
}
