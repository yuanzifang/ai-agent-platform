package com.platform.deploy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_deployment")
public class AgentDeployment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long tenantId;
    private String version;
    private String containerName;
    private String containerId;
    private String image;
    private Integer hostPort;
    private String endpoint;
    private String localEndpoint;
    private String status;
    private Long apiKeyId;
    private String runtimeToken;
    private String lastError;
    private Long deployedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
