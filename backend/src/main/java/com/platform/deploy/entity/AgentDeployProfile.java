package com.platform.deploy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Agent 独立部署资源 Profile。 */
@Data
@TableName("agent_deploy_profile")
public class AgentDeployProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String cpuLimit;
    private String cpuRequest;
    private String memoryLimit;
    private String memoryRequest;
    private Integer pidsLimit;
    private Integer defaultReplicas;
    private Integer maxReplicas;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
