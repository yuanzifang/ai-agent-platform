package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_ocr_cache")
public class KnowledgeOcrCache {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String cacheKey;
    private String text;
    private String tableHtml;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
}
