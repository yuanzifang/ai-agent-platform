package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_tool_binding")
public class AgentToolBinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long toolId;
    private String params;    // JSON：运行时参数值（支持 {{变量}}）
    private Integer enabled;
    private Integer sortOrder;
    private Long tenantId;
    private LocalDateTime createdAt;
}
