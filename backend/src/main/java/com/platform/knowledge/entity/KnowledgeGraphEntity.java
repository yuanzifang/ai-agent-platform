package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_graph_entity")
public class KnowledgeGraphEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private String name;
    private String normalizedName;
    private String entityType;
    private String description;
    /** AUTO：解析时自动抽取；MANUAL：人工创建/编辑。 */
    private String source;
    private BigDecimal confidence;
    private String propertiesJson;
    @JsonIgnore
    private String embedding;
    private String embeddingModel;
    private Integer dimensions;
    private Long documentId;
    private Long chunkId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
