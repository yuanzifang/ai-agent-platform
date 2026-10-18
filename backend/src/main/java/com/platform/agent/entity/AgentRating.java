package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_rating")
public class AgentRating {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long userId;
    private Integer score;
    private String comment;
    private String reply;
    private Long replyBy;
    private LocalDateTime replyAt;
    private Long tenantId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
