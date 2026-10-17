package com.platform.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_attachment")
public class ChatAttachment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private Long messageId;
    private String objectName;
    private String fileName;
    private Long sizeBytes;
    private String contentType;
    private Long tenantId;
    private LocalDateTime createdAt;
}
