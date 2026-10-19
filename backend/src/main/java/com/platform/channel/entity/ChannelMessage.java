package com.platform.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("channel_message")
public class ChannelMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long channelId;
    private Long tenantId;
    private String direction;
    private String externalUserId;
    private String sessionId;
    private String content;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
}
