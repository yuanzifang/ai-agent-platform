package com.platform.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("gateway_route")
public class GatewayRoute {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String routeKey;
    private String apiKeyHash;
    private String apiKeyPrefix;
    private String targetUrl;
    private Integer timeoutMs;
    private Integer retries;
    private Integer rateLimitPerMin;
    private Integer enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
