package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document")
public class KnowledgeDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long knowledgeBaseId;
    private String fileName;
    private String objectName;
    private String status;   // PENDING / PARSING / DONE / FAILED
    private Integer chunkCount;
    private String errorMessage;
    private Integer retryTimes;
    private Long tenantId;
    private LocalDateTime createdAt;
}
