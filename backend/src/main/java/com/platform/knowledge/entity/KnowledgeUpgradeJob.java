package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_upgrade_job")
public class KnowledgeUpgradeJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long knowledgeBaseId;
    private String status; // RUNNING / COMPLETED / FAILED
    private Integer totalDocuments;
    private Integer submittedDocuments;
    private Integer succeededDocuments;
    private Integer failedDocuments;
    private Long createdBy;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
