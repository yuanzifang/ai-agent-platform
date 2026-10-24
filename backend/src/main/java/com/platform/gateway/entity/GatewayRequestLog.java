package com.platform.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("gateway_request_log")
public class GatewayRequestLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long routeId;
    private String method;
    private String path;
    private Integer status;
    private Integer upstreamStatus;
    private Long latencyMs;
    private String error;
    private LocalDateTime createdAt;
}
