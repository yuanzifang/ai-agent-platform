package com.platform.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_node_run")
public class WorkflowNodeRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long tenantId;
    private String nodeId;
    private String nodeType;
    private String name;
    private String status;
    private String inputText;
    private String outputText;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
