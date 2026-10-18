package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_integration_api_key")
public class IntegrationApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private String name;
    private String channel;
    private String keyHash;
    private String keyPrefix;
    private Long serviceUserId;
    private String status;
    private Integer dailyLimit;
    private Integer minuteLimit;
    private LocalDateTime lastUsedAt;
    private Long tenantId;
    private LocalDateTime createdAt;
}
