package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_graph_relation")
public class KnowledgeGraphRelation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private Long fromId;
    private Long toId;
    private String relation;
    private BigDecimal weight;
    private Long documentId;
    private LocalDateTime createdAt;
}
