package com.platform.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_workflow_version")
public class AgentWorkflowVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowId;
    private Long tenantId;
    private String version;
    private String name;
    private String definition;
    private Long createdBy;
    private LocalDateTime createdAt;
}
