package com.platform.observability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_trace")
public class AgentTrace {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String sessionId;
    private Long conversationId;
    private Long agentId;
    private Long userId;
    private String status;
    private String modelName;
    private Long inputTokens;
    private Long outputTokens;
    private Long latencyMs;
    private Long tenantId;
    private LocalDateTime createdAt;
}
