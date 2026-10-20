package com.platform.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("billing_usage")
public class BillingUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Long knowledgeBaseId;
    private String source;   // chat / debug / intelligent / scheduled / embedding / eval
    private String modelName;
    private Long inputTokens;
    private Long outputTokens;
    private Long cachedInputTokens;
    private Long cacheWriteTokens;
    private Long reasoningTokens;
    private BigDecimal cost;
    private LocalDate statDate;
    private LocalDateTime createdAt;
}
