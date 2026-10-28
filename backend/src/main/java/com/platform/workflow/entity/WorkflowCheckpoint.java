package com.platform.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_checkpoint")
public class WorkflowCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long tenantId;
    private String nodeId;
    private String nextNodeId;
    private String status;
    private String contextJson;
    private String outputText;
    private Long inputTokens;
    private Long outputTokens;
    private LocalDateTime createdAt;
}
