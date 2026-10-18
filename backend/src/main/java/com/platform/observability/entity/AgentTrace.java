package com.platform.observability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
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
    private String agentName;             // 名称快照（需求 3.4 Traces 列表）
    private String version;               // 发布版本号
    private String kind;                  // internal / external / eval（需求 3.4）
    private Long userId;
    private String status;
    private String modelName;
    private String inputText;
    private String outputText;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private BigDecimal cost;              // 成本：model 单价（元/千 Token）
    private Long latencyMs;
    private String errorMessage;
    private String tags;                  // JSON 数组：来源细分（调度/调试等）
    private BigDecimal score;             // 在线监控评测回填（需求 3.5）
    private Long tenantId;
    private LocalDateTime createdAt;
}
