package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库成员授权（需求 3.6.3(5)、6.7）：subject_type=USER，permission=VIEW/EDIT。
 */
@Data
@TableName("knowledge_permission")
public class KnowledgePermission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long knowledgeBaseId;
    private String subjectType;
    private Long subjectId;
    private String permission;
    private Long tenantId;
    private LocalDateTime createdAt;
}
