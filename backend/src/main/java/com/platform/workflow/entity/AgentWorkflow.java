package com.platform.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_workflow")
public class AgentWorkflow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long tenantId;
    private String name;
    private String definition;
    private String status;
    private String publishedVersion;
    private String publishedDefinition;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
