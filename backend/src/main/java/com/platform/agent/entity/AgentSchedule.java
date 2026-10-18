package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_schedule")
public class AgentSchedule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private String name;
    private String cronExpr;
    private String inputParams;   // JSON
    private Integer timeoutMs;
    private String notifyType;    // STORE / WEBHOOK
    private String webhookUrl;
    private String status;        // ENABLED / DISABLED
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private String lastStatus;
    private String lastMessage;
    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
