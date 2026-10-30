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
    /** 每日调用配额；0/NULL=不限。 */
    private Integer dailyQuota;
    /** 单请求体上限；0/NULL=全局限制。 */
    private Integer maxBodyBytes;
    /** 逗号分隔的方法白名单；空=全部。 */
    private String allowedMethods;
    /** 换行/逗号分隔的路径前缀；空=全部。 */
    private String allowedPaths;
    /** IP/CIDR 白名单；空=不限制。 */
    private String allowedIps;
    private Long requestCount;
    private LocalDateTime lastUsedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
