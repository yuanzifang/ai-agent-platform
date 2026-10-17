package com.platform.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long agentId;
    private Long userId;
    private String title;
    private String source;
    private LocalDateTime lastActiveAt;
    private String status;
    private Long tenantId;
    private LocalDateTime createdAt;
}
