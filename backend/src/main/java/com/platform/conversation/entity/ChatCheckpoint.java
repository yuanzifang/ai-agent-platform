package com.platform.conversation.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /** RUNNING 开始时需要显式清空上一轮关联，不能沿用 MP 默认的 NOT_NULL 更新策略。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long messageId;
    private Long inputTokens;
    private Long outputTokens;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String citationsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
