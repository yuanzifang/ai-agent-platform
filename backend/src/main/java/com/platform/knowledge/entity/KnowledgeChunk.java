package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long knowledgeBaseId;
    private Integer chunkIndex;
    private Integer parentIndex;
    private String parentContent;
    private String content;
    private String allowedUsers;
    private Integer tenantVisible;
    private String imageUrls;  // JSON
    private Long tenantId;
    private LocalDateTime createdAt;
}
