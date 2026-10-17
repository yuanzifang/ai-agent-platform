package com.platform.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("moderation_record")
public class ModerationRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String direction;
    private String traceId;
    private Long messageId;
    private Long conversationId;
    private String ruleId;
    private String action;
    private String matchedText;
    private Long tenantId;
    private LocalDateTime createdAt;
}
