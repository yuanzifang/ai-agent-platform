package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_favorite")
public class AgentFavorite {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long userId;
    private Long tenantId;
    private LocalDateTime createdAt;
}
