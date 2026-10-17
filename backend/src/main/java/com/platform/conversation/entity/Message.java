package com.platform.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("message")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String traceId;
    private String role;
    private Long parentMessageId;
    private Integer isRegenerated;
    private String content;
    private String attachments;
    private String status;
    private String auditedStatus;
    private Integer isAiGenerated;
    private Integer tokens;
    private BigDecimal cost;
    private Long tenantId;
    private LocalDateTime createdAt;
}
