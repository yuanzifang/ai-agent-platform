package com.platform.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation_share")
public class ConversationShare {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String token;
    private LocalDateTime expiresAt;
    private Long createdBy;
    private Long tenantId;
    private LocalDateTime createdAt;
}
