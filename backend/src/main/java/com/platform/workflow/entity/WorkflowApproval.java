package com.platform.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_approval")
public class WorkflowApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long agentId;
    private Long tenantId;
    private String nodeId;
    private String title;
    private String requiredRole;
    private java.time.LocalDateTime expireAt;
    private String payload;
    private String status;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private String comment;
    private LocalDateTime createdAt;
}
