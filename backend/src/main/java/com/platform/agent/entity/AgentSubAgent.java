package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_sub_agent")
public class AgentSubAgent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long subAgentId;
    private Integer enabled;
    private Integer sortOrder;
    private Long tenantId;
    private LocalDateTime createdAt;
}
