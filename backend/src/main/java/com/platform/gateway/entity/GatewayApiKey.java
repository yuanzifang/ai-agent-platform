package com.platform.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("gateway_api_key")
public class GatewayApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long routeId;
    private String name;
    private String keyHash;
    private String keyPrefix;
    private Integer enabled;
    private LocalDateTime expiresAt;
    private Integer rateLimitPerMin;
    private Long requestCount;
    private LocalDateTime lastUsedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
