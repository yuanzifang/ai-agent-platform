package com.platform.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("billing_account")
public class BillingAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;

    private BigDecimal balance;
    private String currency;
    private LocalDateTime updatedAt;


}
