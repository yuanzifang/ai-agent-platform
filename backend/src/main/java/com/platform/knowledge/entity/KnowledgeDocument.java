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
    private String parseWarning;
    private Integer chunkCount;
    private Long sizeBytes;
    private String sourceSha256;
    private String errorMessage;
    private Integer retryTimes;
    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String parseConfig; // JSON：解析配置覆盖（KB 默认 / 文档上传时选择）
}
