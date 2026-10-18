package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_report")
public class AgentReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long userId;
    private String reason;
    private String status;
    private Long tenantId;
    private LocalDateTime createdAt;
}
