package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 父块内容独立存储：同一父块只存一份，子块仅保留 parent_index。 */
@Data
@TableName("knowledge_parent")
public class KnowledgeParent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long knowledgeBaseId;
    private Integer parentIndex;
    private String content;
    private String documentType;
    private String sectionType;
    private Integer chunkLevel;
    private String domainKeywords; // JSON array
    private Double relevanceScore;
    private String regulatoryCategory;
    private Long tenantId;
    private LocalDateTime createdAt;
}
