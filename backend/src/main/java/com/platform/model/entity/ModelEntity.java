package com.platform.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("model")
public class ModelEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String modelName;
    private String displayName;
    private String purpose;      // CHAT / EMBEDDING / RERANK
    private String capabilities; // JSON 文本
    private String defaultParams;
    private BigDecimal inputPrice;
    private BigDecimal outputPrice;
    private Integer contextWindow;   // 上下文窗口（tokens，0=未知）
    private String status;
    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
