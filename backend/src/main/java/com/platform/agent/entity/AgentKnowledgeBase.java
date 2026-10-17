package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_knowledge_base")
public class AgentKnowledgeBase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long knowledgeBaseId;
    private Integer enabled;
    private String retrievalConfig;
    private Long tenantId;
    private LocalDateTime createdAt;
}
