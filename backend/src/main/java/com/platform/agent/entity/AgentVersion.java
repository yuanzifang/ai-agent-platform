package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_version")
public class AgentVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private String version;
    private String snapshot;  // JSON
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long tenantId;
}
