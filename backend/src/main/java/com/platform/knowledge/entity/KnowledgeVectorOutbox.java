package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_vector_outbox")
public class KnowledgeVectorOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long documentId;
    private String status; // PENDING / DONE / FAILED
    private Integer attempts;
    private LocalDateTime availableAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
