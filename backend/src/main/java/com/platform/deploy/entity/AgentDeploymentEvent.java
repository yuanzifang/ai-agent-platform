package com.platform.deploy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_deployment_event")
public class AgentDeploymentEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long deploymentId;
    private Long tenantId;
    private String action;
    private String status;
    private String version;
    private String containerName;
    private Integer hostPort;
    private String message;
    private Long operatorId;
    private LocalDateTime createdAt;
}
