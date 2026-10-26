package com.platform.deploy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_runtime_key")
public class AgentRuntimeKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long tenantId;
    private String name;
    private String keyHash;
    private String keyPrefix;
    private Integer dailyQuota;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
