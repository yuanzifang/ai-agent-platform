package com.platform.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("billing_bill")
public class BillingBill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;



    private String period;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private Long calls;
    private BigDecimal cost;
    private String status;
    private Long generatedBy;
    private LocalDateTime generatedAt;
}
