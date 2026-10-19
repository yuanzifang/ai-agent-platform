package com.platform.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_run")
public class WorkflowRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowId;
    private Long agentId;
    private Long tenantId;
    private String definition;
    private String inputText;
    private String outputText;
    private String contextJson;
    private String status;
    private String currentNode;
    private String errorMessage;
    private Long userId;
    private Long inputTokens;
    private Long outputTokens;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
