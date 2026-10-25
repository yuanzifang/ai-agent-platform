package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识图谱全库构建任务。 */
@Data
@TableName("knowledge_graph_job")
public class KnowledgeGraphJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private String status;
    private Integer totalDocuments;
    private Integer processedDocuments;
    private Integer totalChunks;
    private Integer processedChunks;
    private Integer entityCount;
    private Integer relationCount;
    private String message;
    private String error;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
