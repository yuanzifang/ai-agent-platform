package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("test_case")
public class TestCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Long conversationId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String question;
    private String actualAnswer;
    private String expectedAnswer;
    private String kind;
    private String source;
    private String status;
    private String tags;
    private String note;
    private String modelName;
    private String traceId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
