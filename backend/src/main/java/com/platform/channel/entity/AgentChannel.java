package com.platform.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_channel")
public class AgentChannel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long tenantId;
    private String name;
    private String type;
    private String token;
    private String outboundUrl;
    private String secret;
    private String configJson;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
