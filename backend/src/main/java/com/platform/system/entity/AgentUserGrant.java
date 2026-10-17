package com.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_user_grant")
public class AgentUserGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long userId;
    private Long tenantId;
    private LocalDateTime createdAt;
}
