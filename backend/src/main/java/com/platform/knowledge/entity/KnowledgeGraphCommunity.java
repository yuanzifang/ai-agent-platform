package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_graph_community")
public class KnowledgeGraphCommunity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private String communityKey;
    private String name;
    private String summary;
    private String entityIds;
    private Integer entityCount;
    private String types;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
