package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_memory")
public class UserMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long agentId;
    private String category;
    private String content;
    private String source;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
