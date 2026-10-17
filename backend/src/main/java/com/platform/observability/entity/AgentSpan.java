package com.platform.observability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_span")
public class AgentSpan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String spanType;  // agent / chain / llm / retrieval / tool
    private String name;
    private String status;
    private Long inputTokens;
    private Long outputTokens;
    private Long latencyMs;
    private String attributes;
    private Long tenantId;
    private LocalDateTime createdAt;
}
