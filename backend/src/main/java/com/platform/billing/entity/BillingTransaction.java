package com.platform.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("billing_transaction")
public class BillingTransaction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;


    private String type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private Long operatorId;
    private String note;
    private LocalDateTime createdAt;

}
