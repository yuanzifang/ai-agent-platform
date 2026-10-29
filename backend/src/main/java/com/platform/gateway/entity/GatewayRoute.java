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
    private String fallbackUrl;
    private String upstreamUrls;
    /** URL -> weight 的 JSON；未配置按主 100、其余 0。 */
    private String upstreamWeights;
    /** AES-GCM 加密的 HMAC 签名密钥；空=关闭请求签名。 */
    private String signingSecret;
    private Integer timeoutMs;
    private Integer retries;
    private Integer rateLimitPerMin;
    /** 健康检查路径；空值按 / 处理。 */
    private String healthPath;
    /** 1=启用主动健康检查；0=关闭（仍然保留被动熔断）。 */
    private Integer healthCheckEnabled;
    /** WebSocket 每路由最大并发连接数；0=不限制。 */
    private Integer wsMaxConnections;
    private Integer enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
