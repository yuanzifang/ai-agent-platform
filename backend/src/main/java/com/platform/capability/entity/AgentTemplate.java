package com.platform.capability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_template")
public class AgentTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String category;
    private String iconUrl;
    private String systemPrompt;
    private String openingStatement;
    private String modelConfig;
    private String agentCapabilities;
    private String memoryConfig;
    private String variables;
    private String tools;              // JSON：[{toolKey}]
    private String skills;             // JSON：[{skillKey}]
    private String knowledgeBases;     // JSON：[{knowledgeBaseId}]
    private Integer isBuiltin;
    private Long tenantId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
