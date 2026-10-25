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
    private String indexVersion;
    private LocalDateTime createdAt;
    private String parseConfig; // JSON：解析配置覆盖（KB 默认 / 文档上传时选择）
}
