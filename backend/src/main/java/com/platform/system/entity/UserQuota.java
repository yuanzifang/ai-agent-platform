package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("user_quota")
public class UserQuota {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long dailyTokenLimit;
    private BigDecimal monthlyCostLimit;
    private Integer enabled;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
