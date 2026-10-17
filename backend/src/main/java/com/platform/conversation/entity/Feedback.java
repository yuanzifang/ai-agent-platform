package com.platform.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("feedback")
public class Feedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private Long conversationId;
    private String traceId;
    private Long userId;
    private String type;
    private String comment;
    private Long tenantId;
    private LocalDateTime createdAt;
}
