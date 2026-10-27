package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_registry")
public class AgentRegistry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentKey;
    private String name;
    private String description;
    private String avatarUrl;
    private String category;
    private String tags;            // JSON
    private String systemPrompt;
    private String openingStatement;
    private String agentCapabilities; // JSON
    private String skills;          // JSON
    private String memoryConfig;    // JSON
    private String modelConfig;     // JSON
    private String tools;           // JSON
    private String knowledgeBaseIds; // JSON
    private String retrievalConfig; // JSON
    private Long resourceProfileId;
    private String subAgentIds;     // JSON
    private String variables;       // JSON
    private Long ownerId;
    private String visibility;
    /** 是否上架 Agent 广场（V17，需求 3.8）。 */
    private Integer listed;
    private String status;          // DRAFT / PUBLISHED / OFFLINE / DELETED
    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
