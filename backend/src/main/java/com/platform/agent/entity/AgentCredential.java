package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_credential")
public class AgentCredential {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private String name;
    private String type;
    private String encryptedContent;
    private Long toolId;
    private String status;
    private Long tenantId;
    private LocalDateTime createdAt;
}
