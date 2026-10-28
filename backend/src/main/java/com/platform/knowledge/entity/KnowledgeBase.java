package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_base")
public class KnowledgeBase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private Long tenantId;
    private Long createdBy;
    /** PRIVATE=只有我 / TENANT=所有团队成员（不含受限成员）/ PARTIAL=部分团队成员 */
    private String visibility;
    private String embeddingModel;
    /** MYSQL / ES */
    private String searchBackend;
    /** PGVECTOR / QDRANT */
    private String vectorBackend;
    private Integer defaultTopK;
    private Integer maxTopK;
    private String indexVersion;
    private LocalDateTime createdAt;
    private String parseConfig; // JSON：解析配置覆盖（KB 默认 / 文档上传时选择）
    /** 是否启用知识图谱：关闭后不自动抽取、不参与 GraphRAG，也不允许进入图谱维护。 */
    private Boolean graphEnabled;
}
