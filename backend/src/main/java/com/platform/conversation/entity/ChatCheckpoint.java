package com.platform.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_checkpoint")
public class ChatCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long tenantId;
    private String traceId;
    private String status;
    private String content;
    private Long messageId;
    private Long inputTokens;
    private Long outputTokens;
    private String citationsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
