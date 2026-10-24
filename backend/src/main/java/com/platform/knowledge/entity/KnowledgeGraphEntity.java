package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_graph_entity")
public class KnowledgeGraphEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private String name;
    private String entityType;
    private String description;
    private Long documentId;
    private Long chunkId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
