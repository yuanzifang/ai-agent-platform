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
    private Long chunkId;
    private String evidence;
    /** AUTO：解析时自动抽取；MANUAL：人工创建/编辑。 */
    private String source;
    private BigDecimal confidence;
    private LocalDateTime createdAt;
}
