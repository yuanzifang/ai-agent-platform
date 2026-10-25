package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 实体在某个文档分段中的一次出现，用于 GraphRAG 证据回溯与多分段召回。 */
@Data
@TableName("knowledge_graph_mention")
public class KnowledgeGraphMention {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private Long entityId;
    private Long documentId;
    private Long chunkId;
    private String quote;
    private String extractor;
    private BigDecimal confidence;
    private LocalDateTime createdAt;
}
