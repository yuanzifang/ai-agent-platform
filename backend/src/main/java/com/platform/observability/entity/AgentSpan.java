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
    private String spanId;         // 应用层生成
    private String parentSpanId;   // 根 Span 为 NULL
    private Long agentId;
    private String spanType;       // agent / chain / llm / retrieval / tool
    private String name;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private Long inputTokens;
    private Long outputTokens;
    private String modelName;      // LLM Span 模型名
    private String toolName;       // Tool Span 工具名
    @Deprecated                     // V11 前旧列，新代码写 durationMs
    private Long latencyMs;
    private String attributes;
    private Long tenantId;
    private LocalDateTime createdAt;
}
