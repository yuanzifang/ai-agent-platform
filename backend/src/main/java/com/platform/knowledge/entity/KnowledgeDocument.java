package com.platform.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document")
public class KnowledgeDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long knowledgeBaseId;
    private String fileName;
    private String objectName;
    private String status;   // PENDING / PARSING / DONE / FAILED
    private String parseWarning;
    private String manualReviewStatus;
    private LocalDateTime manualReviewedAt;
    private Long manualReviewedBy;
    private String manualReviewNote;
    private Integer chunkCount;
    private Long sizeBytes;
    private String sourceSha256;
    private String parseFingerprint;
    private String parserVersion;
    private LocalDateTime parseStartedAt;
    private Long upgradeJobId;
    private String upgradeStatus;
    private Integer upgradeAttempts;
    private String embeddingModel;
    private Integer embeddingDimensions;
    private LocalDateTime vectorVerifiedAt;
    private String errorMessage;
    private Integer retryTimes;
    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String parseConfig; // JSON：解析配置覆盖（KB 默认 / 文档上传时选择）
    /** 仅用于文档列表返回：当前解析结果是否需要按最新解析器版本升级。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean upgradePending;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean manualReviewRequired;
}
